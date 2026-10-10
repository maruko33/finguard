#!/usr/bin/env python3
"""Run Maven against local Compose PostgreSQL using Compose's dotenv parser."""

import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    api = Path(__file__).resolve().parents[1]
    root = api.parents[1]
    if not (root / ".env").is_file():
        sys.exit("Create the repository-root .env from .env.example first.")

    env = os.environ.copy()
    if any(k.startswith("SPRING_DATASOURCE_") or k == "SPRING_APPLICATION_JSON"
           for k in env):
        sys.exit("Unset SPRING_DATASOURCE_* and SPRING_APPLICATION_JSON for local Maven.")

    # The local file is authoritative; stale shell passwords must not override it.
    for key in ("POSTGRES_PASSWORD", "DB_PASSWORD"):
        env.pop(key, None)
    try:
        result = subprocess.run(
            ["docker", "compose", "--project-directory", str(root),
             "--env-file", str(root / ".env"), "-f", str(root / "compose.yaml"),
             "config", "--format", "json"],
            env=env, capture_output=True, text=True, check=True,
        )
        services = json.loads(result.stdout)["services"]
        # `compose config` escapes dollars for reuse as a Compose document.
        # Decode that serialization once before passing values to a process.
        postgres = {key: value.replace("$$", "$") if value is not None else value
                    for key, value in services["postgres"]["environment"].items()}
        password = postgres.get("POSTGRES_PASSWORD")
        if not password:
            sys.exit("Set a nonempty POSTGRES_PASSWORD in the repository-root .env.")
        if "\r" in password or "\n" in password:
            sys.exit("The parsed local password contains a newline; check .env quoting.")
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError):
        # Compose diagnostics can contain interpolated secrets; never echo them.
        sys.exit("Cannot read local Compose configuration. Check Docker Compose and .env syntax.")

    env.update(
        DB_URL="jdbc:postgresql://localhost:5432/" + postgres["POSTGRES_DB"],
        DB_USERNAME=postgres["POSTGRES_USER"],
        DB_PASSWORD=password,
        POSTGRES_PASSWORD=password,
    )
    queue = services["transaction-api"]["environment"].get("SQS_QUEUE_URL")
    if queue is not None:
        env["SQS_QUEUE_URL"] = queue.replace("$$", "$")
    os.chdir(api)
    os.execve(str(api / "mvnw"), [str(api / "mvnw"), *sys.argv[1:]], env)


if __name__ == "__main__":
    main()
