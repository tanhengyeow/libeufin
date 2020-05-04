#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen, PIPE
from time import sleep
import os
import socket

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
#
# 2 Prepare the Ebics transport for the nexus user.
#  -> (a) Upload keys from Nexus to the Bank (INI & HIA),
#     (b) Download key from the Bank (HPB) to the Nexus,
#     and (c) Fetch the bank account owned by that subscriber
#     at the bank.

# 3 Request history from the Nexus to the Bank (C53).
# 4 Verify that history is empty.
# 5 Issue a payment from Nexus
#  -> (a) Prepare & (b) trigger CCT.
# 6 Request history again, from Nexus to Bank.
# 7 Verify that previous payment shows up.

# Nexus user details
USERNAME="person"

# EBICS details
EBICS_URL="http://localhost:5000/ebicsweb"
HOST_ID="HOST01"
PARTNER_ID="PARTNER1"
USER_ID="USER1"
EBICS_VERSION = "H004"

# Subscriber's bank account
SUBSCRIBER_IBAN="GB33BUKB20201555555555"
SUBSCRIBER_BIC="BUKBGB22"
SUBSCRIBER_NAME="Oliver Smith"
BANK_ACCOUNT_LABEL="savings"

def checkPorts(ports):
    for i in ports:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(i)
            s.close()
        except:
            print("Port {} is not available")
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

#-1 Clean databases and start services.
os.chdir("..")
assert(0 == call(["rm", "-f", "sandbox/libeufin-sandbox.sqlite3"]))
assert(0 == call(["rm", "-f", "nexus/libeufin-nexus.sqlite3"]))
DEVNULL = open(os.devnull, "w")

# Start nexus
checkPorts([5001])
nexus = Popen(["./gradlew", "nexus:run"], stdout=PIPE, stderr=PIPE)
for i in range(10):
    try:
      get("http://localhost:5001/")
    except:
        if i == 9:
            nexus.terminate()
            stdout, stderr = nexus.communicate()
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(1)
        continue
    break

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
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(1)
        continue
    break

#0.a
assertResponse(
    post(
        "http://localhost:5000/admin/ebics-host",
        json=dict(
            hostID=HOST_ID,
        ebicsVersion=EBICS_VERSION
        )
    )
)

#0.b
assertResponse(
    post(
        "http://localhost:5000/admin/ebics-subscriber",
        json=dict(
            hostID=HOST_ID,
        partnerID=PARTNER_ID,
        userID=USER_ID
        )
    )
)

#0.c
assertResponse(
    post(
        "http://localhost:5000/admin/ebics-subscriber/bank-account",
        json=dict(
            subscriber=dict(
                hostID=HOST_ID,
                partnerID=PARTNER_ID,
                userID=USER_ID
        ),
            iban=SUBSCRIBER_IBAN,
            bic=SUBSCRIBER_BIC,
            name=SUBSCRIBER_NAME,
        label=BANK_ACCOUNT_LABEL
        )
    )
)

#1.a
assertResponse(
    post(
        "http://localhost:5001/users/{}".format(USERNAME),
        json=dict(
        password="secret"
        )
    )
)

#1.b
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}".format(USERNAME),
        json=dict(
        ebicsURL=EBICS_URL,
        hostID=HOST_ID,
        partnerID=PARTNER_ID,
        userID=USER_ID
        )
    )
)
#2.a
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/sendINI".format(USERNAME),
        json=dict()
    )
)

assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/sendHIA".format(USERNAME),
        json=dict()
    )
)

#2.b
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/sync".format(USERNAME),
        json=dict()
    )
)

#2.c
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/fetch-accounts".format(USERNAME),
        json=dict()
    )
)

#3
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/collect-transactions-c53".format(USERNAME),
        json=dict()
    )
)

#4
resp = assertResponse(
    get(
        "http://localhost:5001/users/{}/history".format(USERNAME)
    )
)
assert(len(resp.json().get("payments")) == 0)

#5.a
assertResponse(
    post(
        "http://localhost:5001/users/{}/prepare-payment".format(USERNAME),
        json=dict(
            creditorIban="FR7630006000011234567890189",
            creditorBic="AGRIFRPP",
            creditorName="Jacques La Fayette",
            debitorIban=SUBSCRIBER_IBAN,
            debitorBic=SUBSCRIBER_BIC,
            debitorName=SUBSCRIBER_NAME,
        subject="integration test",
        sum=1
        )
    )
)

#5.b
assertResponse(
    post("http://localhost:5001/ebics/execute-payments")
)

#6
assertResponse(
    post(
        "http://localhost:5001/ebics/subscribers/{}/collect-transactions-c53".format(USERNAME),
        json=dict()
    )
)

resp = assertResponse(
    get(
        "http://localhost:5001/users/{}/history".format(USERNAME)
    )
)
assert(len(resp.json().get("payments")) == 1)

print("Test passed!")
