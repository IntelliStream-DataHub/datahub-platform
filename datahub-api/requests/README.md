# API request collection

One [JetBrains HTTP Client](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html)
file per API operation, grouped by resource (`api.datasets/`, `api.resources/`,
`api.timeseries/`, and so on). Open any `.http` file in IntelliJ and run a request from the
gutter. The `Content-Type` and body of each request is a worked example of that endpoint's
wire shape, so they are useful to read even without running them.

The data in them is synthetic. It describes a fictional site, `Example Site Rack 5`, with
switches, compute nodes, storage, smart plugs and room sensors, plus some oil and gas
equipment. The resources reference each other by `externalId`, so a create in
`api.resources/` sets up ids that the `api.timeseries/` requests then attach series to.

## Variables

Requests use `{{hostname}}`, `{{wsHostname}}` and `{{token}}`. The first two come from
[`http-client.env.json`](http-client.env.json), which is committed and holds no secrets. Note
that `hostname` includes the scheme and `wsHostname` does not.

`{{token}}` is a bearer token, so it lives in `http-client.private.env.json` next to this
file. That name is gitignored, and it must stay that way: it holds a real credential.

Create it yourself:

```json
{
  "dev": {
    "token": "<paste an access token here>"
  }
}
```

## Getting a token

Against the local stack from [GETTING_STARTED.md](../../GETTING_STARTED.md), the demo user
`foo` works:

```bash
curl -s localhost:8090/realms/datahub/protocol/openid-connect/token \
  -d grant_type=password -d client_id=datahub-client -d client_secret=changeme \
  -d username=foo -d password=foo | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
```

Tokens are short-lived, so expect to repeat this. If a request comes back 401, that is
almost always the reason.
