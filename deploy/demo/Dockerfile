# Runtime image for the 3W demo seeder/feeder (LOCAL DEV).
#
# Deliberately tiny: the demo payloads under data/ are pre-generated (see
# generate/README.md), so this image needs no pandas, no pyarrow and no network at run
# time — only the DataHub SDK and the Python standard library.
#
# The SDK comes from PyPI, which publishes a compiled wheel per platform — so this needs no
# Rust toolchain and works on x86_64, arm64 (incl. Apple Silicon) and musl alike.
#
# Pinned exactly, on purpose: the demo replays payloads captured against a known SDK, and an
# unpinned build would silently pick up a new major one day and fail mid-seed. Bump it
# deliberately and re-run the demo; demo.seed logs the version it is running.
FROM docker.io/library/python:3.13-slim

RUN pip install --no-cache-dir --root-user-action=ignore \
      intellistream-datahub-sdk==0.2.0

WORKDIR /app
COPY data/ /app/data/
COPY demo/ /app/demo/

# PYTHONUNBUFFERED so the seed's progress reaches `compose logs` as it happens rather than
# in one lump at exit — this container's log IS its user interface when seeding fails.
ENV PYTHONUNBUFFERED=1 DEMO_DATA_DIR=/app/data

# One image, two services: `command:` picks demo.seed or demo.feed.
ENTRYPOINT ["python", "-m"]
CMD ["demo.seed"]
