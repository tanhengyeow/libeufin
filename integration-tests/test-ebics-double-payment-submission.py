#!/usr/bin/env python3

from subprocess import check_call
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

assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
    )
)
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
assertResponse(
    post(
        "http://localhost:5001/users",
        headers=dict(Authorization=ADMIN_AUTHORIZATION_HEADER),
        json=dict(username=USERNAME, password=PASSWORD),
    )
)
print("creating bank connection")
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
print("connecting")
assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/connect",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/ebics/import-accounts",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
resp = assertResponse(
    post(
        "http://localhost:5001/bank-accounts/{}/payment-initiations".format(
            BANK_ACCOUNT_LABEL
        ),
        json=dict(
            iban="FR7630006000011234567890189",
            bic="AGRIFRPP",
            name="Jacques La Fayette",
            subject="integration test",
            amount="EUR:1",
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
PREPARED_PAYMENT_UUID = resp.json().get("uuid")
if PREPARED_PAYMENT_UUID == None:
    fail("Payment UUID not received")

assertResponse(
    post(
        f"http://localhost:5001/bank-accounts/{BANK_ACCOUNT_LABEL}/payment-initiations/{PREPARED_PAYMENT_UUID}/submit",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
# mark the payment as not submitted directly
# into the database.
check_call(["sqlite3", NEXUS_DB, f"UPDATE PaymentInitiations SET submitted = false WHERE id = '{PREPARED_PAYMENT_UUID}'"]) 

# Re-submission of the same payment.  Fails with 500 now,
# because nexus doesn't know the EBICS error code reported
# by the sandbox.
assertResponse(
    post(
        f"http://localhost:5001/bank-accounts/{BANK_ACCOUNT_LABEL}/payment-initiations/{PREPARED_PAYMENT_UUID}/submit",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("Test passed!")
