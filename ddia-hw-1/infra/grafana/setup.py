"""Prepare the Grafana password and a token for the dashboard generator."""

import base64
import json
import os
from pathlib import Path
import secrets
import sys
import urllib.error
import urllib.request

PASSWORD_FILE = Path("/run/grafana-admin/password")
TOKEN_FILE = Path("/run/grafana-token/token")
ACCOUNT_NAME = "dashboard-generator"
TOKEN_NAME = "compose-generator"
FOLDER_UID = "ddia-hw-1"
GRAFANA_UID = 472
GENERATOR_UID = 10001


def write_secret(path, value, owner):
    os.umask(0o077)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(value)
    temporary.chmod(0o600)
    os.chown(temporary, owner, -1)
    os.chown(path.parent, owner, -1)
    path.parent.chmod(0o700)
    temporary.replace(path)


def request(authorization, method, path, payload=None):
    base_url = os.environ.get("GRAFANA_URL", "http://grafana:3000").rstrip("/")
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(base_url + path, data=body, method=method,
                                 headers={"Authorization": authorization, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else None
    except urllib.error.HTTPError as error:
        if method == "GET" and error.code == 404:
            return None
        raise RuntimeError(f"Grafana {method} {path}: HTTP {error.code}") from None


def prepare_credentials():
    password = PASSWORD_FILE.read_text() if PASSWORD_FILE.exists() else secrets.token_urlsafe(32)
    write_secret(PASSWORD_FILE, password, GRAFANA_UID)
    print("Grafana password is ready.")


def bootstrap():
    credentials = ("bootstrap-admin:" + PASSWORD_FILE.read_text().strip()).encode()
    admin = "Basic " + base64.b64encode(credentials).decode()
    accounts = request(admin, "GET", "/api/serviceaccounts/search?query=" + ACCOUNT_NAME)
    account = next((item for item in accounts["serviceAccounts"] if item["name"] == ACCOUNT_NAME), None)
    if account is None:
        account = request(admin, "POST", "/api/serviceaccounts", {"name": ACCOUNT_NAME, "role": "Editor"})

    folder_path = "/api/folders/" + FOLDER_UID
    if request(admin, "GET", folder_path) is None:
        request(admin, "POST", "/api/folders", {"uid": FOLDER_UID, "title": "DDIA HW1"})
        request(admin, "POST", folder_path + "/permissions", {"items": [
            {"userId": account["id"], "permission": 2},
            {"role": "Viewer", "permission": 1},
        ]})

    tokens_path = f"/api/serviceaccounts/{account['id']}/tokens"
    for token in request(admin, "GET", tokens_path):
        if token["name"] == TOKEN_NAME:
            request(admin, "DELETE", tokens_path + "/" + str(token["id"]))
    token = request(admin, "POST", tokens_path, {"name": TOKEN_NAME})["key"]
    write_secret(TOKEN_FILE, token, GENERATOR_UID)
    print("Grafana folder and generator token are ready.")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["credentials"]:
            prepare_credentials()
        elif sys.argv[1:] == ["bootstrap"]:
            bootstrap()
        else:
            raise ValueError("Expected credentials or bootstrap")
    except Exception as error:
        print(f"Grafana setup failed: {error}", file=sys.stderr)
        sys.exit(1)
