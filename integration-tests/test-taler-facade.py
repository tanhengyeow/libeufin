#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen
from time import sleep
import os
import socket
import hashlib
import base64

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
BANK_CONNECTION_LABEL = "my-ebics"

# Databases
NEXUS_DB="/tmp/test-nexus.sqlite3"
SANDBOX_DB="/tmp/test-sandbox.sqlite3"

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

# Clean databases and start services.
os.chdir("..")
assert 0 == call(["rm", "-f", SANDBOX_DB])
assert 0 == call(["rm", "-f", NEXUS_DB])
DEVNULL = open(os.devnull, "w")

assert 0 == call(
    ["nexus", "superuser", "admin", "--password=x", "--db-name={}".format(NEXUS_DB)]
)

# start nexus
checkPorts([5001])
nexus = Popen(["nexus", "serve", "--db-name={}".format(NEXUS_DB)])
for i in range(10):
    try:
        get("http://localhost:5001/")
    except:
        if i == 9:
            nexus.terminate()
            print("Nexus timed out")
            print("{}\n{}".format(stdout.decode(), stderr.decode()))
            exit(77)
        sleep(2)
        continue
    break

# start sandbox
checkPorts([5000])
sandbox = Popen(["sandbox", "serve", "--db-name={}".format(SANDBOX_DB)])
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

# make ebics host at sandbox
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)

# make ebics subscriber at sandbox
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
    )
)

# link bank account to ebics subscriber at sandbox
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

# make a new nexus user.
assertResponse(
    post(
        "http://localhost:5001/users",
        headers=dict(Authorization=ADMIN_AUTHORIZATION_HEADER),
        json=dict(username=USERNAME, password=PASSWORD),
    )
)

print("creating bank connection")

# make a ebics bank connection for the new user.
assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(
            name=BANK_CONNECTION_LABEL,
            source="new",
            type="ebics",
            data=dict(
                ebicsURL=EBICS_URL, hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID
            ),
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("connecting")

assertResponse(
    post(
        "http://localhost:5001/bank-connections/{}/connect".format(BANK_CONNECTION_LABEL),
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)


# fetch bank account information
assertResponse(
    post(
        "http://localhost:5001/bank-connections/{}/ebics/import-accounts".format(BANK_CONNECTION_LABEL),
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

# create new facade
assertResponse(
    post(
        "http://localhost:5001/facades",
        json=dict(
            name="my-facade",
            type="taler-wire-gateway",
            creator=USERNAME,
            config=dict(
                bankAccount=BANK_ACCOUNT_LABEL,
                bankConnection=BANK_CONNECTION_LABEL,
                reserveTransferLevel="UNUSED",
                intervalIncremental="UNUSED"
            )
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

# todo: call /transfer + call /history/outgoing

assertResponse(
    post(
        "http://localhost:5001/facades/my-facade/taler/transfer",
        json=dict(
            request_uid="0",
            amount="EUR:1",
            exchange_base_url="http//url",
            wtid="nice",
            credit_account="payto://iban/THEIBAN/THEBIC?name=theName"
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )

)

# Checks if that crashes the _incoming_ history too.  It does NOT!
#assertResponse(
#    post(
#        "http://localhost:5001/facades/my-facade/taler/admin/add-incoming",
#        json=dict(
#            amount="EUR:1",
#            reserve_pub="my-reserve-pub",
#            debit_account="payto://iban/DONATOR/MONEY?name=TheDonator"
#        ),
#        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
#    )
#)

print("sleeping 100s")
sleep(100)

nexus.terminate()
sandbox.terminate()
print("Test passed!")
