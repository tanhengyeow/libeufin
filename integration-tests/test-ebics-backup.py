#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen, PIPE
from time import sleep
import os
import socket
import hashlib
import base64

# Steps implemented in this test.
#
# 0 Prepare sandbox.
#  -> (a) Make a EBICS host, (b) make a EBICS subscriber
#     for the test runner, and (c) assign a IBAN to such
#     subscriber.
#
# 1 Prepare nexus.
#  -> (a) Make a Nexus user, (b) make a EBICS subscriber
#     associated to that user

# 2 Save and restore a backup.
# 3 Send INI & HIA to the bank.

# Nexus user details
USERNAME = "person"
PASSWORD = "y"
USER_AUTHORIZATION_HEADER = "basic {}".format(
    base64.b64encode(b"person:y").decode("utf-8")
)

# Admin authentication
ADMIN_AUTHORIZATION_HEADER = "basic {}".format(
    base64.b64encode(b"admin:x").decode("utf-8")
)

# EBICS details
EBICS_URL = "http://localhost:5000/ebicsweb"
HOST_ID = "HOST01"
PARTNER_ID = "PARTNER1"
USER_ID = "USER1"
EBICS_VERSION = "H004"

# Subscriber's bank account
SUBSCRIBER_IBAN = "GB33BUKB20201555555555"
SUBSCRIBER_BIC = "BUKBGB22"
SUBSCRIBER_NAME = "Oliver Smith"
BANK_ACCOUNT_LABEL = "savings"


def fail(msg):
    print(msg)
    nexus.terminate()
    sandbox.terminate()
    exit(1)


def checkPorts(ports):
    for i in ports:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", i))
            s.close()
        except:
            print("Port {} is not available".format(i))
            exit(77)


def assertResponse(response):
    if response.status_code != 200:
        print("Test failed on URL: {}".format(response.url))
        # stdout/stderr from both services is A LOT of text.
        # Confusing to dump all that to console.
        print("Check nexus.log and sandbox.log, probably under /tmp")
        nexus.terminate()
        sandbox.terminate()
        exit(1)
    # Allows for finer grained checks.
    return response


# -1 Clean databases and start services.
os.chdir("..")
assert 0 == call(["rm", "-f", "sandbox/libeufin-sandbox.sqlite3"])
assert 0 == call(["rm", "-f", "nexus/libeufin-nexus.sqlite3"])
DEVNULL = open(os.devnull, "w")

assert 0 == call(
    ["./gradlew", "nexus:run", "--console=plain", "--args=superuser admin --password x"]
)

# Start nexus
checkPorts([5001])
nexus = Popen(
    ["./gradlew", "nexus:run", "--console=plain", "--args=serve"],
    stdout=PIPE,
    stderr=PIPE,
)
for i in range(10):
    try:
        get("http://localhost:5001/")
    except:
        if i == 9:
            nexus.terminate()
            stdout, stderr = nexus.communicate()
            print("Nexus timed out")
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(2)
        continue
    break
# Start sandbox
checkPorts([5000])
sandbox = Popen(["./gradlew", "sandbox:run"], stdout=PIPE, stderr=PIPE)
for i in range(10):
    try:
        get("http://localhost:5000/")
    except:
        if i == 9:
            nexus.terminate()
            sandbox.terminate()
            stdout, stderr = nexus.communicate()
            print("Sandbox timed out")
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(2)
        continue
    break

# 0.a
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)

# 0.b
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
    )
)

# 0.c
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
            iban=SUBSCRIBER_IBAN,
            bic=SUBSCRIBER_BIC,
            name=SUBSCRIBER_NAME,
            label=BANK_ACCOUNT_LABEL,
        ),
    )
)

# 1.a, make a new nexus user.

assertResponse(
    post(
        "http://localhost:5001/users",
        headers=dict(Authorization=ADMIN_AUTHORIZATION_HEADER),
        json=dict(username=USERNAME, password=PASSWORD),
    )
)

print("creating bank connection")

# 1.b, make a ebics bank connection for the new user.
assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(
            name="my-ebics",
            source="new",
            type="ebics",
            data=dict(
                ebicsURL=EBICS_URL, hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID
            ),
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("saving a backup copy")

resp = assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/export-backup",
        json=dict(passphrase="secret"),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

# resp is the backup object.

print("wait 3 seconds before restoring the backup")
sleep(3)

assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(name="my-ebics-restored", data=resp.json(), passphrase="secret", source="backup"),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("send ini & hia with restored connection")

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-restored/ebics/send-ini",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-restored/ebics/send-hia",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

nexus.terminate()
sandbox.terminate()
print("Test passed!")
