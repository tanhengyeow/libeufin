#!/usr/bin/env python3

from requests import post, get

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

#0.a
resp = post(
    "http://localhost:5000/admin/ebics-host",
    json=dict(
	hostID=HOST_ID,
	ebicsVersion=EBICS_VERSION
    )
)

assert(resp.status_code == 200)

#0.b
resp = post(
    "http://localhost:5000/admin/ebics-subscriber",
    json=dict(
        hostID=HOST_ID,
	partnerID=PARTNER_ID,
	userID=USER_ID
    )
)

assert(resp.status_code == 200)

#0.c
resp = post(
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
assert(resp.status_code == 200)

#1.a
resp = post(
    "http://localhost:5001/users/{}".format(USERNAME),
    json=dict(
	password="secret"
    )
)

assert(resp.status_code == 200)

#1.b
resp = post(
    "http://localhost:5001/ebics/subscribers/{}".format(USERNAME),
    json=dict(
	ebicsURL=EBICS_URL,
	hostID=HOST_ID,
	partnerID=PARTNER_ID,
	userID=USER_ID
    )
)

assert(resp.status_code == 200)

#2.a
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/sendINI".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

resp = post(
    "http://localhost:5001/ebics/subscribers/{}/sendHIA".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

#2.b
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/sync".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

#2.c
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/fetch-accounts".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

#3
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/collect-transactions-c53".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

#4
resp = get(
    "http://localhost:5001/users/{}/history".format(USERNAME)
)
assert(resp.status_code == 200)
assert(len(resp.json().get("payments")) == 0)

#5.a
resp = post(
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
assert(resp.status_code == 200)

#5.b
resp = post("http://localhost:5001/ebics/execute-payments")
assert(resp.status_code == 200)

#6
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/collect-transactions-c53".format(USERNAME),
    json=dict()
)
assert(resp.status_code == 200)

resp = get(
    "http://localhost:5001/users/{}/history".format(USERNAME)
)
assert(resp.status_code == 200)
assert(len(resp.json().get("payments")) == 1)
