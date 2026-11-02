// Keep OAuth query values out of the displayed URL and browser history while
// retaining a user-initiated fallback for browsers that do not open the
// verified Android App Link automatically.
const callback = new URL(window.location.href);
const codes = callback.searchParams.getAll("code");
const states = callback.searchParams.getAll("state");
const returnLink = document.getElementById("return-to-streamvault");

if (callback.protocol === "https:" && codes.length === 1 && codes[0] && states.length === 1 && states[0] &&
    !callback.searchParams.has("error")) {
  returnLink.href = `intent://${callback.host}${callback.pathname}${callback.search}#Intent;scheme=https;package=com.streamvault.app;end`;
}

window.history.replaceState(null, "", callback.pathname);
