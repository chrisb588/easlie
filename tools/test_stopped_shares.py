"""Device regression: external single/multiple shares must cold-start the saved board.

Install the debug and androidTest APKs first. Run with --serial DEVICE if needed.
The app's existing files are moved aside while stopped and restored in finally.
Do not use easlie on the device during this test. No existing boards are edited.
"""

import argparse
import json
import shlex
import subprocess
import time
import uuid


APP = "com.chrisb588.easlie"
PROVIDER = "content://com.chrisb588.easlie.test.images"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])

    def shell(*parts, input=None, check=True):
        return subprocess.run(
            adb + ["shell", shlex.join(parts)], input=input, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check,
            timeout=30,
        )

    def private(script, **kwargs):
        return shell("run-as", APP, "sh", "-c", script, **kwargs)

    def stop():
        shell("am", "force-stop", APP)
        assert not shell("pidof", APP, check=False).stdout.strip(), "App is still running"

    def write(path, contents):
        private("cat > " + shlex.quote(path), input=contents)

    def manifest(board):
        return json.loads(private(f"cat files/boards/{board}/board.json").stdout)

    backup = "files.share-test-backup-" + uuid.uuid4().hex
    stop()
    # Record the move before creating any test files. A failed move leaves data untouched.
    had_files = private("test -d files", check=False).returncode == 0
    if had_files:
        private(f"mv files {backup}")
    try:
        private("mkdir files")
        shell("content", "call", "--uri", PROVIDER, "--method", "create-task-fixtures")
        for mode, count in (("single", 1), ("multiple", 2)):
            stop()
            saved, other = "saved-" + mode, "other-" + mode
            index = ["easlie-board-collection", "schemaVersion=1", f"activeBoard={saved}"]
            # Put the other board first: startup must use activeBoard, not list order.
            for board in (other, saved):
                index.append(f"board={board}|{board.encode().hex()}")
                private(f"mkdir -p files/boards/{board}")
                viewport = {"centerX": 0, "centerY": 0, "zoom": 1}
                write(f"files/boards/{board}/board.json", json.dumps({
                    "schemaVersion": 1, "items": [],
                    "viewports": {"fullScreen": viewport, "floating": viewport},
                }))
            write("files/boards.index", "\n".join(index) + "\n")
            shell("am", "start", "-W", "-n", APP + ".test/com.chrisb588.easlie.ShareSenderActivity",
                  "--es", "mode", mode)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                imported = manifest(saved)["items"]
                if len(imported) == count:
                    break
                time.sleep(.1)
            else:
                raise AssertionError(f"{mode}: expected {count} images, got {len(imported)}")
            assert not manifest(other)["items"], "Share was redirected to another board"
            assert f"activeBoard={saved}\n" in private("cat files/boards.index").stdout
            assert len({item["assetId"] for item in imported}) == count
            for item in imported:
                assert private(f"test -s files/boards/{saved}/assets/{item['assetId']}", check=False).returncode == 0
            # A launcher return must not replay the delivered share intent.
            shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
                  "android.intent.category.LAUNCHER", "-n", APP + "/.MainActivity")
            time.sleep(.5)
            assert manifest(saved)["items"] == imported, "Launcher replayed the share"
            print(f"PASS stopped-app {mode} share: {count} image(s), saved destination, no replay", flush=True)
    finally:
        stop()
        # Only the isolated files created above are removed; original files stay in backup.
        private("rm -rf files")
        if had_files:
            private(f"mv {backup} files")


if __name__ == "__main__":
    main()
