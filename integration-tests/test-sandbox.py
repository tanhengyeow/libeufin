#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen, PIPE
from time import sleep
import os
import socket
import hashlib
import base64

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
        print("Check sandbox.log, probably under /tmp")
        sandbox.terminate()
        exit(1)
    # Allows for finer grained checks.
    return response


# -1 Clean databases and start the service.
os.chdir("..")
assert 0 == call(["rm", "-f", "sandbox/libeufin-sandbox.sqlite3"])
DEVNULL = open(os.devnull, "w")

# Start sandbox
checkPorts([5000])
sandbox = Popen(["./gradlew", "sandbox:run"], stdout=PIPE, stderr=PIPE)
for i in range(10):
    try:
        get("http://localhost:5000/")
    except:
        if i == 9:
            sandbox.terminate()
            print("Sandbox timed out")
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(2)
        continue
    break

# Create a Ebics host.
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)

# Create a new subscriber.
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
    )
)

# Assign a bank account to such subscriber.
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

# Generate a few payments related to such account.
for i in range(1, 3):
    assertResponse(
        post(
            "http://localhost:5000/admin/payments",
            json=dict(
                creditorIban="ES9121000418450200051332",
                creditorBic="BIC",
                creditorName="Creditor Name",
                debitorIban="GB33BUKB20201555555555",
                debitorBic="BIC",
                debitorName="Debitor Name",
                amount="0.99",
                currency="EUR",
                subject="test service #{}".format(i)
            )
        )
    )

resp = assertResponse(
    get("http://localhost:5000/admin/payments")
)

print(resp.text)

sandbox.terminate()
print("\nTest passed!")
