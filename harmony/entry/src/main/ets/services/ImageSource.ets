// An absent or identical thumbnail needs only one authenticated image request.
export function embeddedImage(url: string, thumbnailUrl: string): string {
  return thumbnailUrl || url;
}
export function previewImage(url: string, thumbnailUrl: string): string {
  return url !== embeddedImage(url, thumbnailUrl) ? url : '';
}
