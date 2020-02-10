#!/usr/bin/env python3
import base64
import fileinput
import google.auth.transport.requests
from google.oauth2 import id_token

# Use this script to verify a token on the client

def run():
    buf = []
    for line in fileinput.input():
        buf.append(line.rstrip('\n'))
    buf.append("===")
    audience = "https://secureinit.local"
    token = ''.join(buf)
    request = google.auth.transport.requests.Request()
    payload = id_token.verify_token(token, request=request, audience=audience)
    print(payload)

if __name__ == '__main__':
    run()
