# SPDX-License-Identifier: AGPL-3.0-or-later
import http.client
import json

# Replace with actual hostname and token
base_url = "api-{{project}}.example.com:443"
from_project = "your-project"
to_project = "your-project"
from_token = "<paste-your-bearer-token-here>"
to_token = ""

from_path = base_url.replace("{{project}}", from_project)
conn = http.client.HTTPSConnection(from_path)

# Set headers
headers = {
    'Authorization': f'Bearer {from_token}',
    'Content-Type': 'application/json'
}

# The data to be sent in the POST request
data = {
    "items": [
        {
            "externalId": "your_external_id",
            "start": "2024-09-01T00:00Z"
        }
    ]
}

# Convert Python dictionary to JSON format
json_data = json.dumps(data)

# Make the POST request
conn.request("POST", "/timeseries/data/list", body=json_data, headers=headers)

def handle_response(response):
    response_data = response.read().decode()
    print("status: " + str(response.status))
    if response.status >= 400:
        print(response_data)
    else:
        result = json.loads(response_data)
        for item in result["items"]:
            print(len(item["datapoints"]))
            print((item["datapoints"][0]))

            if item.get("nextCursor"):
                print("Cursor:" + item.get("nextCursor"))
                data["items"][0]["cursor"] = item["nextCursor"]
                new_data = json.dumps(data)
                conn.request("POST", "/timeseries/data/list", body=new_data, headers=headers)
                handle_response( response = conn.getresponse() )


# Get the response
handle_response( response = conn.getresponse() )