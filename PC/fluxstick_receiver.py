import json, socket, vgamepad as vg

HOST = "0.0.0.0"
PORT = 26760

gamepad = vg.VX360Gamepad()
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))
print(f"FluxStick receiver listening on UDP {PORT}")

def pressed(d, k): return bool(d.get(k, False))

while True:
    data, addr = sock.recvfrom(8192)
    try:
        d = json.loads(data.decode("utf-8"))

        gamepad.left_joystick_float(
            float(d.get("lx",0)), -float(d.get("ly",0))
        )
        gamepad.right_joystick_float(
            float(d.get("rx",0)), -float(d.get("ry",0))
        )

        mapping = {
            "a": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
            "b": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
            "x": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
            "y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
            "lb": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
            "rb": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "up": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
            "down": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
            "left": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
            "right": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
            "select": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
            "start": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        }

        for key, btn in mapping.items():
            if pressed(d,key):
                gamepad.press_button(button=btn)
            else:
                gamepad.release_button(button=btn)

        gamepad.update()
    except Exception as e:
        print("packet error:", e)
