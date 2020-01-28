#!/bin/bash

# This program allocates a new customer into the Sandbox
# and Nexus systems.

usage () {
  printf "Usage: ./prepare_subscriber.sh <salt>\n"
  printf "<salt> is any chars used to form user and partner IDs.\n"
}

exe_echo () {
  echo \$ "$@"; "$@"
}

if [ -z "$1" ]; then
  usage
  exit 1
fi

printf "\nFirst: the new subscriber must exist in the Sandbox.  For\n"
printf "this reason, we invoke the \"admin\" part of its API.\n"
printf "Press <enter> key to proceed.."
read x
printf "\n"

exe_echo libeufin-cli admin add-subscriber \
  --sandbox-url http://localhost:5000/admin/add/subscriber \
  --user-id "user$1" \
  --partner-id "partner$1" \
  --host-id "host$1" \
  --name "name $1" && sleep 1

printf "\nSecond: the Nexus must persist the same information,\n"
printf "and associate a numerical ID to it.\n"
printf "Press <enter> key to proceed.."
read x

printf "\n"
exe_echo libeufin-cli ebics new-subscriber \
  --ebics-url http://localhost:5000/ebicsweb \
  --user-id "user$1" \
  --partner-id "partner$1" \
  --host-id "host$1" && sleep 1


# Finally, the numerical ID just created can be used
# to drive all the EBICS operations.  Request it with:

printf "\nA new subscriber was created at the Sandbox and\n"
printf "at the Nexus.  Press <enter> for more useful commands.."
read x

printf "\nSee again your ID:\n"
printf "\tcurl http://localhost:5001/ebics/subscribers\n"

printf "Request INI, HIA, and HPB, with:\n"
printf "\tlibeufin-cli ebics ini --customer-id=\$ID_NUMBER\n"
printf "\tlibeufin-cli ebics hia --customer-id=\$ID_NUMBER\n"
printf "\tlibeufin-cli ebics sync --customer-id=\$ID_NUMBER\n\n"
