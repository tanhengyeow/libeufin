# Helpers for the integration tests.

from subprocess import check_call, Popen, PIPE
import socket
from requests import post, get
from time import sleep
import atexit


def checkPort(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", port))
        s.close()
    except:
        print("Port {} is not available".format(i))
        exit(77)


def startSandbox():
    check_call(["rm", "-f", "sandbox/libeufin-sandbox.sqlite3"])
    check_call(["./gradlew", "sandbox:assemble"])
    checkPort(5000)
    sandbox = Popen(["./gradlew",
        "sandbox:run",
            "--console=plain",
        "--args=serve"],
            stdout=open("sandbox-stdout.log", "w"),
            stderr=open("sandbox-stderr.log", "w"),
            )
    atexit.register(lambda: sandbox.terminate())
    for i in range(10):
        try:
            get("http://localhost:5000/")
        except:
            if i == 9:
                stdout, stderr = nexus.communicate()
                print("Sandbox timed out")
                print("{}\n{}".format(stdout.decode(), stderr.decode()))
                exit(77)
            sleep(2)
            continue
        break


def startNexus(dbfile):
    check_call(["rm", "-f", "nexus/{}".format(dbfile)])
    check_call(
        [
            "./gradlew",
            "nexus:assemble",
        ]
    )
    check_call(
        [
            "./gradlew",
            "nexus:run",
            "--console=plain",
            "--args=superuser admin --password x --db-name={}".format(dbfile),
        ]
    )
    checkPort(5001)
    nexus = Popen(
        [
            "./gradlew",
            "nexus:run",
            "--console=plain",
            "--args=serve --db-name={}".format(dbfile),
        ],
        stdout=open("nexus-stdout.log", "w"),
        stderr=open("nexus-stderr.log", "w"),
    )
    atexit.register(lambda: nexus.terminate())
    for i in range(10):
        try:
            get("http://localhost:5001/")
        except:
            if i == 9:
                nexus.terminate()
                print("Nexus timed out")
                exit(77)
            sleep(1)
            continue
        break
    return nexus
