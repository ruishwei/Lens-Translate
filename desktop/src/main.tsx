import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import Capture from "./Capture";
import "./styles.css";
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {location.hash.startsWith("#/capture") ? <Capture /> : <App />}
  </React.StrictMode>,
);
