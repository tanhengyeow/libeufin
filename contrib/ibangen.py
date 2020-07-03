#!/usr/bin/env python3

# Copyright (c) 2020 Taler Systems S.A.
# 
# Permission to use, copy, modify, and/or distribute this software for any
# purpose with or without fee is hereby granted.
# 
# THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
# REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
# AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
# INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
# LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
# OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
# PERFORMANCE OF THIS SOFTWARE.

import click
import random

def complete_iban(s):
    n = int("".join([str(int(x, 26)) for x in (s[4:] + s[0:2] + "00")]))
    c = 98 - (n % 97)
    return (s[:2] + str(c).ljust(2, "0") + s[4:]).upper()


@click.command()
def geniban():
    bban = "12345678"
    accno = "".join((str(random.randint(0, 9)) for _ in range(10)))
    iban = complete_iban("DE00" + bban + accno)
    print(iban)

if __name__ == '__main__':
    geniban()
