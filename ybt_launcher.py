"""PyInstaller entry point for the YBT Army Windows desktop bundle."""
import os
from pathlib import Path
import sys


def main():
    bundle_dir = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    executable_dir = Path(sys.executable).resolve().parent
    app_file = bundle_dir / "ybt_app.py"

    if not app_file.is_file():
        raise FileNotFoundError(f"Bundled Streamlit app not found: {app_file}")

    # Keep chat history beside the executable, not in PyInstaller's temp folder.
    os.environ.setdefault("YBT_CHATS_DIR", str(executable_dir / "chats"))
    os.environ.setdefault("STREAMLIT_BROWSER_GATHER_USAGE_STATS", "false")

    from streamlit.web import cli as stcli

    sys.argv = [
        "streamlit",
        "run",
        str(app_file),
        "--global.developmentMode=false",
        "--server.headless=true",
        "--server.address=127.0.0.1",
    ]
    raise SystemExit(stcli.main())


if __name__ == "__main__":
    main()
