const API_BASE = import.meta.env.VITE_API_BASE || "/api";

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(parseError(text, response.status));
  }
  if (response.headers.get("content-type")?.includes("application/json")) {
    return response.json();
  }
  return response.text();
}

async function postForm(path, formData) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    body: formData
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(parseError(text, response.status));
  }
  return response.json();
}

function parseError(payload, status) {
  try {
    const data = JSON.parse(payload);
    return data.error ?? JSON.stringify(data);
  } catch {
    return payload || `Request failed (${status})`;
  }
}

export { API_BASE, request, postForm };
