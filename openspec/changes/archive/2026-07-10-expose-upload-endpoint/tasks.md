# Tasks: expose-upload-endpoint

## 1. Upload route

- [x] 1.1 Add `UploadResponse(postId, status)` + its JSON format (co-locate with the other response
      types in `CatalogJson`).
- [x] 1.2 Add `UploadRoutes` — `POST /uploads`: derive the media class from the request `Content-Type`
      (`?mediaType=` override), stream `entity.dataBytes` into the injected upload function, answer
      `201 { postId, status:"pending" }`; map an upstream failure to `502`. Parameterized over the
      upload function so it route-tests with no Apollo/Hermes.

## 2. Wire into the runtime

- [x] 2.1 In `Main`, construct `UploadService` (`ApolloObjectUploader(apolloClient)` +
      `HermesMediaJobPublisher(hermesClient, topic)` + the sharded `postFor` factory) and compose
      `UploadRoutes.routes` into the HTTP surface.

## 3. Test & verify

- [x] 3.1 Route test (`ScalatestRouteTest`, fake upload fn): `201` + body on success; media class
      derived from `Content-Type` and overridden by `?mediaType=`; `502` on a failed upload.
- [x] 3.2 Extend `RunnableServiceE2EIT` (or add a focused IT) to drive the upload through the bound
      `POST /uploads` route rather than calling `UploadService` directly — proving the HTTP seam.
- [x] 3.3 Full gate green (compile under cranked flags, `scalafixAll --check`, core + server tests);
      checker pass over the route + wiring; apply fixes; re-verify.
