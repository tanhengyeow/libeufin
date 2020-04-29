#!/usr/bin/env python3

from requests import post, get

# Steps implemented in this test.
#
# 1 Prepare the Sandbox to run the test.
#  -> Make a EBICS host, and make a EBICS subscriber
#     for the test runner.
#
# 2 Prepare the Nexus to run the test.
#  -> Make a Nexus user, and make a EBICS transport
#     entity associated with that user.
#
# 3 Upload keys from Nexus to the Bank (INI & HIA)
# 4 Download key from the Bank (HPB) to the Nexus
#
# 5 Request history from the Nexus to the Bank (C53).
# 6 Verify that history is empty.
# 7 Issue a payment from Nexus (Prepare & trigger CCT)
# 8 Request history again, from Nexus to Bank.
# 9 Verify that previous payment shows up.


# Nexus user details
USERNAME="person"

# EBICS details
EBICS_URL="http://localhost:5000/ebicsweb"
HOST_ID="HOST01"
PARTNER_ID="PARTNER1"
USER_ID="USER1"
EBICS_VERSION = "H004"

#0 Prepare Sandbox (make Ebics host & one subscriber)
resp = post(
    "http://localhost:5000/admin/ebics-host",
    json=dict(
	hostID=HOST_ID,
	ebicsVersion=EBICS_VERSION
    )
)

assert(resp.status_code == 200)

resp = post(
    "http://localhost:5000/admin/ebics-subscriber",
    json=dict(
        hostID=HOST_ID,
	partnerID=PARTNER_ID,
	userID=USER_ID
    )
)

assert(resp.status_code == 200)

#1 Create a Nexus user

resp = post(
    "http://localhost:5001/users/{}".format(USERNAME),
    json=dict(
	password="secret"
    )
)

assert(resp.status_code == 200)

#2 Create a EBICS user
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

#3 Upload keys to the bank INI & HIA
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

#4 Download keys from the bank HPB
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/sync".format(USERNAME),
    json=dict()
)

assert(resp.status_code == 200)

#5 Request history via EBICS
resp = post(
    "http://localhost:5001/ebics/subscribers/{}/collect-transactions-c53".format(USERNAME),
    json=dict()
)

assert(resp.status_code == 200)

resp = get(
    "http://localhost:5001/users/{}/history".format(USERNAME)
)

assert(
    resp.status_code == 200 and \
    len(resp.json().get("payments")) == 0
)

#6 Prepare a payment (via pure Nexus service)
resp = post(
    "http://localhost:5001/users/{}/prepare-payment".format(USERNAME),
    json=dict()
)

assert(resp.status_code == 200)



#7 Execute such payment via EBICS
#8 Request history again via EBICS
