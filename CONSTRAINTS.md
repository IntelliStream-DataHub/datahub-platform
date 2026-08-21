# Constraints

Invariants this codebase is meant to hold, and which a change should not quietly break.
These are not style preferences: each one exists because breaking it has a cost that shows
up somewhere other than the file you are editing.

## Backend

1. **Node entities share one interface.** The entities encoding the different kinds of graph
   node should present the same shape, so code that walks the graph does not need to know
   which kind it is holding.

2. **Postgres is the primary database.** It holds the authoritative state. If something is
   valid in Postgres it should be valid in the other stores, which are derived from it rather
   than sources of truth in their own right.

3. **Validate before it goes async.** Anything published to a consumer should already be known
   good. A consumer that receives an invalid message has no useful way to reject it: the
   caller is long gone, and the failure surfaces as a stuck subscription rather than a bad
   request.

4. **One type label per node.** `typeLabels` encode the node's type and the mapping is one to
   one, so a node has exactly one. Other labels are free to change.

## Frontend

1. **Forms follow one style.** The JavaScript forms should stay consistent with each other
   rather than each solving presentation again.

2. **Call the API directly, not through the console.** The console's backend-for-frontend
   layer is being removed. New features should call datahub-api from the browser rather than
   adding to the proxy.

3. **Reuse Thymeleaf fragments.** Prefer an existing fragment over a new component that
   renders the same thing.
