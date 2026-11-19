/**
 * What html-to-image draws for a picture it cannot fetch (a card icon whose URL
 * 404s, for example): one transparent pixel. Without a placeholder the library
 * sets the image's source to an empty string, the image errors, and the whole
 * export rejects with a bare event instead of an Error. Every capture passes
 * this as `imagePlaceholder` so a missing picture leaves a blank, not no image.
 */
export const EXPORT_IMAGE_PLACEHOLDER =
  "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
