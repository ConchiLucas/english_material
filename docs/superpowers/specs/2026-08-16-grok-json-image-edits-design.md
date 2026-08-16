# Grok JSON Image Editing Design

## Goal

Make storyboard image generation use the request format supported by the selected image model without changing the existing Gemini behavior:

- Grok Imagine image generation without references continues to use JSON `POST /v1/images/generations`.
- Grok Imagine image editing with references uses JSON `POST /v1/images/edits` and embeds references as base64 data URIs.
- Gemini and other non-Grok models continue to use the existing multipart `POST /v1/images/edits` request.

## Provider Routing

The existing model-name check remains the routing boundary: a normalized model name beginning with `grok-imagine-image` is treated as Grok Imagine. This covers model variants such as `grok-imagine-image-quality` while keeping all other providers on the existing code path.

For requests without references, provider routing does not change. For requests with references:

1. Grok Imagine builds an application/json edit request.
2. Every other model builds the existing multipart/form-data edit request.

## Grok Edit Payload

The JSON edit body reuses the Grok-compatible common fields:

- `model`
- `prompt`, with the negative prompt appended as an `Avoid:` paragraph
- `response_format`
- `aspect_ratio`

It must not send the DALL-E/Gemini-only fields `negative_prompt`, `quality`, or `size`.

Reference images are encoded as objects of the form:

```json
{
  "type": "image_url",
  "url": "data:image/png;base64,..."
}
```

A single reference is sent in `image`. Two or three references are sent in `images`, preserving their input order.

## More Than Three References

Grok supports at most three edit references. When the application supplies four to eight references, the service creates one deterministic composite reference board and sends that board as the single `image` value.

The board:

- includes every supplied reference exactly once and preserves input order;
- uses a transparent-free neutral background;
- uses a deterministic near-square grid;
- scales each image down proportionally and letterboxes it inside its cell without cropping;
- has no labels or added text;
- stays within the service's existing byte and pixel safety limits;
- is encoded as PNG and embedded as a base64 data URI.

The board uses the requested output dimensions when valid, falling back to the service defaults. This keeps its aspect ratio aligned with the requested storyboard image and prevents unbounded intermediate images.

## Validation and Failure Behavior

Existing reference validation remains in force before request construction: maximum eight images, supported MIME types, per-image byte limit, total byte limit, valid dimensions, and pixel limit.

Composite construction validates that each source can be decoded. An unreadable reference produces the existing style of bounded `IllegalArgumentException` and no provider call is made. Provider responses continue through the existing bounded response reader, base64 decoder, image validation, normalization, retry policy, and secret-safe error handling.

## Compatibility

Gemini behavior is intentionally unchanged, including multipart field names `image`, `image1`, and `image2`. Existing generation requests and response processing are unchanged apart from selecting the new Grok edit request builder when references are present.

## Tests

Tests will establish the following behavior before implementation:

1. One Grok reference produces JSON `/v1/images/edits` with `image` as a data URI.
2. Two or three Grok references produce ordered `images` entries.
3. Four or more Grok references produce one composite `image` containing all source colors/images.
4. Grok edit JSON contains `aspect_ratio` and excludes DALL-E/Gemini-only fields.
5. Gemini/non-Grok reference editing remains multipart with the existing field names.
6. Invalid composite inputs fail before any HTTP request.

