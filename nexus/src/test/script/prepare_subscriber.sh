#!/bin/bash

# This program allocates a new customer into the Sandbox
# and Nexus systems.


usage () {
  printf "Usage: ./prepare_subscriber.sh <salt>\n"
  printf "<salt> is any chars used to form user/partner/host IDs.\n"
}

continue_input () {
  read -p "Continue? Press <Y/n> followed by <enter>" x
  if test "$x" = "n"; then
    printf "Aborting..\n"
    exit
  fi
}

exe_echo () {
  echo \$ "$@"; "$@"
}

if ! which libeufin-cli > /dev/null; then
  printf "Please make sure 'libeufin-cli' is in the PATH\n"
  exit 1
fi

if [ -z "$1" ]; then
  usage
  exit 1
fi

ACCOUNT_ID="account-$1"
BANK_BASE_URL="http://localhost:5000"
NEXUS_BASE_URL="http://localhost:5001"

printf "\nFirst: the new subscriber must exist at the Bank.\n"
printf "For this reason, we invoke the \"admin\" part of its API.\n"
printf "Press <enter> to proceed.."
read x
printf "\n"

exe_echo libeufin-cli admin add-host \
  --host-id "host$1" \
  --ebics-version "2.5" $BANK_BASE_URL && sleep 1

exe_echo libeufin-cli admin add-subscriber \
  --user-id "user$1" \
  --partner-id "partner$1" \
  --host-id "host$1" \
  --name "\"name $1\"" $BANK_BASE_URL && sleep 1

continue_input

printf "\nSecond: the Nexus must persist the same information\n"
printf "and associate an alpha-numerical ID to it.\nPress <enter> to proceed.."
read x

printf "\n"
exe_echo libeufin-cli ebics new-subscriber \
  --account-id $ACCOUNT_ID \
  --ebics-url http://localhost:5000/ebicsweb \
  --user-id "user$1" \
  --partner-id "partner$1" \
  --host-id "host$1" $NEXUS_BASE_URL && sleep 1

continue_input

printf "Below are some common commands:\n"

printf "\nSee again your account ID:\n"
printf "\tlibeufin-cli ebics subscribers $NEXUS_BASE_URL\n"

printf "Request INI, HIA, and HPB, with:\n"
printf "\tlibeufin-cli ebics ini --account-id=$ACCOUNT_ID $NEXUS_BASE_URL\n"
printf "\tlibeufin-cli ebics hia --account-id=$ACCOUNT_ID $NEXUS_BASE_URL\n"
printf "\tlibeufin-cli ebics sync --account-id=$ACCOUNT_ID $NEXUS_BASE_URL\n\n"
