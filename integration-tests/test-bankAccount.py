#!/usr/bin/env python3

from requests import post, get
from time import sleep
import os
import hashlib
import base64

from util import startNexus, startSandbox

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

# Databases
NEXUS_DB = "test-nexus.sqlite3"


def fail(msg):
    print(msg)
    exit(1)


def assertResponse(response):
    if response.status_code != 200:
        print("Test failed on URL: {}".format(response.url))
        # stdout/stderr from both services is A LOT of text.
        # Confusing to dump all that to console.
        print("Check nexus.log and sandbox.log, probably under /tmp")
        exit(1)
    # Allows for finer grained checks.
    return response


startNexus(NEXUS_DB)
startSandbox()

# make ebics host at sandbox
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)

# make new ebics subscriber at sandbox
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
    )
)

# give a bank account to such subscriber, at sandbox
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

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/connect",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

## download list of offered accounts

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/fetch-accounts",
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

## show such accounts
listOfferedAccounts = assertResponse(
    get(
        "http://localhost:5001/bank-connections/my-ebics/accounts",
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

listOfferedAccountsBefore = assertResponse(
    get(
        "http://localhost:5001/bank-connections/my-ebics/accounts",
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

## import one

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/import-account",
        json=dict(offeredAccountId=BANK_ACCOUNT_LABEL, nexusBankAccountId="savings-at-nexus!"),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

## make sure the imported account shows up

listOfferedAccountsAfter = assertResponse(
    get(
        "http://localhost:5001/bank-connections/my-ebics/accounts",
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER)
    )
)

for el in listOfferedAccountsAfter.json().get("accounts"):
    if el.get("nexusBankAccountId") == "savings-at-nexus":
        exit(0)
        print("Test passed!")

exit(1)
