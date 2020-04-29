#!/usr/bin/env python3

from requests import post, get

#1 Create a Nexus user
USERNAME="person"

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
	ebicsURL="http://localhost:5000/ebicsweb",
	hostID="HOST01",
	partnerID="PARTNER1",
	userID="USER1"
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

#5 Request history
#6 Prepare a payment
#7 Execute such payment via EBICS
#8 Request history again
#9 Exit

# The Nexus should connect to the Sandbox for
# these tests!
