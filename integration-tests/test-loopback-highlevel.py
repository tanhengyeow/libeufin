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
# 0 Prepare nexus.
#  -> (a) Make a Nexus user, (b) make a loopback bank connection
#     associated to that user

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

BANK_ACCOUNT_LABEL = "savings"

# Databases
NEXUS_DB="test-nexus.sqlite3"

def fail(msg):
    print(msg)
    nexus.terminate()
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
        exit(1)
    # Allows for finer grained checks.
    return response


# -1 Clean databases and start services.
os.chdir("..")
assert 0 == call(["rm", "-f", "nexus/{}".format(NEXUS_DB)])
DEVNULL = open(os.devnull, "w")

assert 0 == call(
    ["./gradlew", "nexus:run", "--console=plain", "--args=superuser admin --password x --db-name={}".format(NEXUS_DB)]
)

# Start nexus
checkPorts([5001])
nexus = Popen(
    ["./gradlew", "nexus:run", "--console=plain", "--args=serve --db-name={}".format(NEXUS_DB)],
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

# 0.a, make a new nexus user.
assertResponse(
    post(
        "http://localhost:5001/users",
        headers=dict(Authorization=ADMIN_AUTHORIZATION_HEADER),
        json=dict(username=USERNAME, password=PASSWORD),
    )
)

print("creating bank connection")

# 0.b, make a ebics bank connection for the new user.
assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(
            name="my-loopback",
            source="new",
            type="loopback",
            data=dict(
                iban="myIBAN",
                bic="myBIC",
                holder="Account Holder Name",
                account="my-bank-account"
            )
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

nexus.terminate()
print("Test passed!")
