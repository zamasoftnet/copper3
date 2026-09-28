# REST multipart upload limits

The REST server uses Commons FileUpload 1.6.0 and rejects multipart requests
before untrusted input can consume unbounded memory or disk space.

The following JVM system properties override the defaults:

| Property | Default |
| --- | ---: |
| `jp.cssj.server.rest.multipart.maxParts` | 128 |
| `jp.cssj.server.rest.multipart.maxPartHeaderBytes` | 512 bytes |
| `jp.cssj.server.rest.multipart.maxRequestBytes` | 512 MiB |
| `jp.cssj.server.rest.multipart.maxFileBytes` | 256 MiB |
| `jp.cssj.server.rest.multipart.maxFormFieldBytes` | 1 MiB |

All values must be positive. The request limit must exceed the file limit,
the file limit must exceed the form-field limit, and the configurable part
header limit cannot exceed 8192 bytes. Invalid values fail server startup.

Limit violations return HTTP 413, malformed multipart input returns HTTP 400,
and multipart I/O failures return HTTP 500. Responses do not expose parser or
filesystem details.
