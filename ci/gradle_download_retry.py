"""One retry of the identical build command for explicit transient HTTP download failures."""
import re
import subprocess
import time


def retryable_download(text):
    transient = re.search(r'(?:HTTP response code:|Received status code)\s*(429|502|503|504)\b', text)
    diagnostic = re.search(r'(?im)^e: |^error: |AssertionError|There were failing tests|test failures', text)
    return bool(transient and not diagnostic)


def run_gradle(command, cwd, path, timeout):
    deadline = time.monotonic() + timeout
    for attempt in (1, 2):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(command, timeout)
        attempt_path = path.with_name(path.stem + f'.attempt-{attempt}.log')
        try:
            with attempt_path.open('w', encoding='utf-8', newline='\n') as log:
                result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                        timeout=remaining, check=False)
        finally:
            # Preserve every attempt; keep the original collector path as the final attempt.
            if attempt_path.exists():
                path.write_bytes(attempt_path.read_bytes())
        if result.returncode == 0:
            return
        if attempt == 2 or not retryable_download(path.read_text(encoding='utf-8')):
            raise subprocess.CalledProcessError(result.returncode, command)
        print(f'{path.stem}: explicit transient download failure; retrying identical command once', flush=True)
