import json, socket, vgamepad as vg

HOST = "0.0.0.0"
PORT = 26760

gamepad = vg.VX360Gamepad()
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))
print(f"FluxStick receiver listening on UDP {PORT}")

def pressed(d, k):
    val = d.get(k, False)
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)):
        return val > 0.5
    if isinstance(val, str):
        return val.lower() in ("true", "1", "yes")
    return False

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
    "lsb": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
    "rsb": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    "select": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
    "back": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
    "start": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
    "guide": vg.XUSB_BUTTON.XUSB_GAMEPAD_GUIDE,
}

count = 0
while True:
    data, addr = sock.recvfrom(8192)
    try:
        d = json.loads(data.decode("utf-8"))
        count += 1

        active = [k for k in mapping.keys() if pressed(d, k)]
        if pressed(d, "lt"): active.append("lt")
        if pressed(d, "rt"): active.append("rt")

        if active:
            print(f"[{count}] Active inputs from {addr[0]}: {active}")

        # Joysticks
        gamepad.left_joystick_float(
            float(d.get("lx", 0)), -float(d.get("ly", 0))
        )
        gamepad.right_joystick_float(
            float(d.get("rx", 0)), -float(d.get("ry", 0))
        )

        # Triggers
        lt_val = 1.0 if pressed(d, "lt") else 0.0
        rt_val = 1.0 if pressed(d, "rt") else 0.0
        gamepad.left_trigger_float(lt_val)
        gamepad.right_trigger_float(rt_val)

        # Digital Buttons
        for key, btn in mapping.items():
            if pressed(d, key):
                gamepad.press_button(button=btn)
            else:
                gamepad.release_button(button=btn)

        gamepad.update()
    except Exception as e:
        print("packet error:", e)
