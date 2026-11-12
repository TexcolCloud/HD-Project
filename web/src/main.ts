import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import "./style.css";
import "./styles/dispatch.css";
import "./styles/managerAssist.css";
createApp(App).use(router).mount("#app");
