import { useEffect, useRef } from "react";

function useEventSource(url, { onMessage, onError, enabled = true } = {}) {
  const savedOnMessage = useRef(onMessage);
  const savedOnError = useRef(onError);

  useEffect(() => {
    savedOnMessage.current = onMessage;
    savedOnError.current = onError;
  }, [onMessage, onError]);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    const source = new EventSource(url);
    source.addEventListener("progress", (event) => {
      savedOnMessage.current?.(event);
    });
    source.onerror = (event) => {
      source.close();
      savedOnError.current?.(event);
    };
    return () => {
      source.close();
    };
  }, [url, enabled]);
}

export default useEventSource;
