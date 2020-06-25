#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen, PIPE
from time import sleep
import os
import socket
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
EBICS_VERSION = "H004"

# Bank connection 1 details
BC1_HOST_ID = "HOST01"
BC1_PARTNER_ID = "PARTNER1"
BC1_USER_ID = "USER1"

# Bank connection 1 subscribers' bank accounts
BC1_SUBSCRIBER1_IBAN = "GB33BUKB20201222222222"
BC1_SUBSCRIBER1_BIC = "BUKBGB11"
BC1_SUBSCRIBER1_NAME = "Oliver Smith"
BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL = "bc1sub1savings"

BC1_SUBSCRIBER2_IBAN = "GB33BUKB20201333333333"
BC1_SUBSCRIBER2_BIC = "BUKBGB22"
BC1_SUBSCRIBER2_NAME = "John Doe"
BC1_SUBSCRIBER2_BANK_ACCOUNT_LABEL = "bc1sub2savings"

BC1_SUBSCRIBER3_IBAN = "GB33BUKB20201444444444"
BC1_SUBSCRIBER3_BIC = "BUKBGB33"
BC1_SUBSCRIBER3_NAME = "Peter John"
BC1_SUBSCRIBER3_BANK_ACCOUNT_LABEL = "bc1sub3savings"

# Bank connection 2 details
BC2_HOST_ID = "HOST02"
BC2_PARTNER_ID = "PARTNER2"
BC2_USER_ID = "USER2"

# Bank connection 2 subscribers' bank accounts
BC2_SUBSCRIBER1_IBAN = "GB33BUKB20201555555555"
BC2_SUBSCRIBER1_BIC = "BUKBGB44"
BC2_SUBSCRIBER1_NAME = "Mary Smith"
BC2_SUBSCRIBER1_BANK_ACCOUNT_LABEL = "bc2sub1savings"

BC2_SUBSCRIBER2_IBAN = "GB33BUKB20201666666666"
BC2_SUBSCRIBER2_BIC = "BUKBGB55"
BC2_SUBSCRIBER2_NAME = "David Doe"
BC2_SUBSCRIBER2_BANK_ACCOUNT_LABEL = "bc2sub2savings"

# Bank connection 3 details
BC3_HOST_ID = "HOST03"
BC3_PARTNER_ID = "PARTNER3"
BC3_USER_ID = "USER3"

# Bank connection 3 subscribers' bank accounts
BC3_SUBSCRIBER1_IBAN = "GB33BUKB20201777777777"
BC3_SUBSCRIBER1_BIC = "BUKBGB66"
BC3_SUBSCRIBER1_NAME = "Zack Low"
BC3_SUBSCRIBER1_BANK_ACCOUNT_LABEL = "bc3sub1savings"

# Bank connection 4 details
BC4_HOST_ID = "HOST04"
BC4_PARTNER_ID = "PARTNER4"
BC4_USER_ID = "USER4"

# Bank connection 4 subscribers' bank accounts
BC4_SUBSCRIBER1_IBAN = "GB33BUKB20201888888888"
BC4_SUBSCRIBER1_BIC = "BUKBGB77"
BC4_SUBSCRIBER1_NAME = "Sally Go"
BC4_SUBSCRIBER1_BANK_ACCOUNT_LABEL = "bc4sub1savings"

# Databases
NEXUS_DB="test-nexus.sqlite3"

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


startNexus("nexus-testenv.sqlite3")
startSandbox()

# 0.a Create EBICS hosts
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=BC1_HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=BC2_HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=BC3_HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=dict(hostID=BC4_HOST_ID, ebicsVersion=EBICS_VERSION),
    )
)

# 0.b Create EBICS subscribers
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=BC1_HOST_ID, partnerID=BC1_PARTNER_ID, userID=BC1_USER_ID),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=BC2_HOST_ID, partnerID=BC2_PARTNER_ID, userID=BC2_USER_ID),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=BC3_HOST_ID, partnerID=BC3_PARTNER_ID, userID=BC3_USER_ID),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=dict(hostID=BC4_HOST_ID, partnerID=BC4_PARTNER_ID, userID=BC4_USER_ID),
    )
)

# 0.c Associates new bank accounts to subscribers.

# BC1
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC1_HOST_ID, partnerID=BC1_PARTNER_ID, userID=BC1_USER_ID),
            iban=BC1_SUBSCRIBER1_IBAN,
            bic=BC1_SUBSCRIBER1_BIC,
            name=BC1_SUBSCRIBER1_NAME,
            label=BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL
        ),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC1_HOST_ID, partnerID=BC1_PARTNER_ID, userID=BC1_USER_ID),
            iban=BC1_SUBSCRIBER2_IBAN,
            bic=BC1_SUBSCRIBER2_BIC,
            name=BC1_SUBSCRIBER2_NAME,
            label=BC1_SUBSCRIBER2_BANK_ACCOUNT_LABEL,
        ),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC1_HOST_ID, partnerID=BC1_PARTNER_ID, userID=BC1_USER_ID),
            iban=BC1_SUBSCRIBER3_IBAN,
            bic=BC1_SUBSCRIBER3_BIC,
            name=BC1_SUBSCRIBER3_NAME,
            label=BC1_SUBSCRIBER3_BANK_ACCOUNT_LABEL,
        ),
    )
)
# BC2
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC2_HOST_ID, partnerID=BC2_PARTNER_ID, userID=BC2_USER_ID),
            iban=BC2_SUBSCRIBER1_IBAN,
            bic=BC2_SUBSCRIBER1_BIC,
            name=BC2_SUBSCRIBER1_NAME,
            label=BC2_SUBSCRIBER1_BANK_ACCOUNT_LABEL,
        ),
    )
)
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC2_HOST_ID, partnerID=BC2_PARTNER_ID, userID=BC2_USER_ID),
            iban=BC2_SUBSCRIBER2_IBAN,
            bic=BC2_SUBSCRIBER2_BIC,
            name=BC2_SUBSCRIBER2_NAME,
            label=BC2_SUBSCRIBER2_BANK_ACCOUNT_LABEL,
        ),
    )
)
# BC3
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC3_HOST_ID, partnerID=BC3_PARTNER_ID, userID=BC3_USER_ID),
            iban=BC3_SUBSCRIBER1_IBAN,
            bic=BC3_SUBSCRIBER1_BIC,
            name=BC3_SUBSCRIBER1_NAME,
            label=BC3_SUBSCRIBER1_BANK_ACCOUNT_LABEL,
        ),
    )
)
# BC4
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=dict(
            subscriber=dict(hostID=BC4_HOST_ID, partnerID=BC4_PARTNER_ID, userID=BC4_USER_ID),
            iban=BC4_SUBSCRIBER1_IBAN,
            bic=BC4_SUBSCRIBER1_BIC,
            name=BC4_SUBSCRIBER1_NAME,
            label=BC4_SUBSCRIBER1_BANK_ACCOUNT_LABEL,
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
            name="my-ebics-1",
            source="new",
            type="ebics",
            data=dict(
                ebicsURL=EBICS_URL, hostID=BC1_HOST_ID, partnerID=BC1_PARTNER_ID, userID=BC1_USER_ID
            ),
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(
            name="my-ebics-2",
            source="new",
            type="ebics",
            data=dict(
                ebicsURL=EBICS_URL, hostID=BC2_HOST_ID, partnerID=BC2_PARTNER_ID, userID=BC2_USER_ID
            ),
        ),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections",
#         json=dict(
#             name="my-ebics-3",
#             source="new",
#             type="ebics",
#             data=dict(
#                 ebicsURL=EBICS_URL, hostID=BC3_HOST_ID, partnerID=BC3_PARTNER_ID, userID=BC3_USER_ID
#             ),
#         ),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections",
#         json=dict(
#             name="my-ebics-4",
#             source="new",
#             type="ebics",
#             data=dict(
#                 ebicsURL=EBICS_URL, hostID=BC4_HOST_ID, partnerID=BC4_PARTNER_ID, userID=BC4_USER_ID
#             ),
#         ),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

print("connecting")

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-1/connect",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-2/connect",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections/my-ebics-3/connect",
#         json=dict(),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections/my-ebics-4/connect",
#         json=dict(),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

print("fetching")

# 2.c, fetch bank account information
assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-1/fetch-accounts",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-2/fetch-accounts",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections/my-ebics-3/import-accounts",
#         json=dict(),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )
# assertResponse(
#     post(
#         "http://localhost:5001/bank-connections/my-ebics-4/import-accounts",
#         json=dict(),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

# # 3, ask nexus to download history
# assertResponse(
#     post(
#         f"http://localhost:5001/bank-accounts/{BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL}/fetch-transactions",
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

# # 4, make sure history is empty
# resp = assertResponse(
#     get(
#         f"http://localhost:5001/bank-accounts/{BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL}/transactions",
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )
# if len(resp.json().get("transactions")) != 0:
#     fail("unexpected number of transactions")

# # 5.a, prepare a payment
# resp = assertResponse(
#     post(
#         "http://localhost:5001/bank-accounts/{}/payment-initiations".format(
#             BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL
#         ),
#         json=dict(
#             iban="FR7630006000011234567890189",
#             bic="AGRIFRPP",
#             name="Jacques La Fayette",
#             subject="integration test",
#             amount="EUR:1",
#         ),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )
# PREPARED_PAYMENT_UUID = resp.json().get("uuid")
# if PREPARED_PAYMENT_UUID == None:
#     fail("Payment UUID not received")

# # 5.b, submit prepared statement
# assertResponse(
#     post(
#         f"http://localhost:5001/bank-accounts/{BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL}/payment-initiations/{PREPARED_PAYMENT_UUID}/submit",
#         json=dict(),
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

# # 6, request history after payment submission
# assertResponse(
#     post(
#         f"http://localhost:5001/bank-accounts/{BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL}/fetch-transactions",
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

# resp = assertResponse(
#     get(
#         f"http://localhost:5001/bank-accounts/{BC1_SUBSCRIBER1_BANK_ACCOUNT_LABEL}/transactions",
#         headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
#     )
# )

# if len(resp.json().get("transactions")) != 1:
#     fail("Unexpected number of transactions; should be 1")


try:
    input("press enter to stop LibEuFin test environment ...")
except:
    pass
print("exiting!")
