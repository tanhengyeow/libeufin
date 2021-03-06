#!/bin/bash

# Such template sets an env up using the Python CLI.
# The setup goes until exchanging keys with the sandbox.

set -e

EBICS_HOST_ID=ebicshost
EBICS_PARTNER_ID=ebicspartner
EBICS_USER_ID=ebicsuser
EBICS_BASE_URL="$SANDBOX_URL/ebicsweb"

IBAN=x
BIC=y
PERSON_NAME=z
ACCOUNT_NAME=a

NEXUS_USER=u
NEXUS_PASSWORD=p
NEXUS_BANK_CONNECTION_NAME=b

if test -z $1; then
  echo usage: ./setup-template.sh PATH-TO-NEXUS-DB
  exit 1
fi

########## setup sandbox #############

# make ebics host at sandbox
echo Making a ebics host at the sandbox
sleep 2
./libeufin-cli \
  sandbox \
    make-ebics-host \
      --host-id=$EBICS_HOST_ID \
      $SANDBOX_URL

# activate a ebics subscriber on that host
echo Activating the ebics subscriber at the sandbox
sleep 2
./libeufin-cli \
  sandbox \
    activate-ebics-subscriber \
      --host-id=$EBICS_HOST_ID \
      --partner-id=$EBICS_PARTNER_ID \
      --user-id=$EBICS_USER_ID \
      $SANDBOX_URL

# give a bank account to such user
echo Giving a bank account to such subscriber
./libeufin-cli \
  sandbox \
    associate-bank-account \
      --iban=$IBAN \
      --bic=$BIC \
      --person-name=$PERSON_NAME \
      --account-name=$ACCOUNT_NAME \
      --ebics-user-id=$EBICS_USER_ID \
      --ebics-host-id=$EBICS_HOST_ID \
      --ebics-partner-id=$EBICS_PARTNER_ID \
      $SANDBOX_URL
sleep 2

########## setup nexus #############

# create a user
echo "Creating a nexus user (giving time to settle)"
nexus superuser --db-name $1 --password $NEXUS_PASSWORD $NEXUS_USER
sleep 2

# create a bank connection
echo Creating a bank connection for such user
./libeufin-cli \
  bank-connection \
    new-ebics-connection \
      --connection-name $NEXUS_BANK_CONNECTION_NAME \
      --ebics-url $EBICS_BASE_URL \
      --host-id $EBICS_HOST_ID \
      --partner-id $EBICS_PARTNER_ID \
      --ebics-user-id $EBICS_USER_ID \
      --nexus-user-id $NEXUS_USER \
      --nexus-password $NEXUS_PASSWORD \
      $NEXUS_URL
sleep 2

# Bootstrapping such connection
echo Bootstrapping the bank connection
./libeufin-cli \
  bank-connection \
    bootstrap-bank-connection \
      --connection-name $NEXUS_BANK_CONNECTION_NAME \
      --nexus-user-id $NEXUS_USER \
      --nexus-password $NEXUS_PASSWORD \
      $NEXUS_URL
