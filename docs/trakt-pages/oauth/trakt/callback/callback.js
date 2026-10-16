// Keep OAuth query values out of the displayed URL and browser history.
// No code/state values are read, rendered, forwarded, or stored by this page.
window.history.replaceState(null, "", window.location.pathname);
