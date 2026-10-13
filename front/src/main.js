import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import router from "./router"
import ElementPlus from "element-plus"
import zhCn from "element-plus/es/locale/lang/zh-cn"
import "element-plus/dist/index.css"
import "@/assets/main.css" // 引入全局优化样式
import "@/styles/home-metrics.css" // 首页 metric 卡片共享样式（预警数字红色）
import permissionDirective from "./utils/permission" // 引入自定义权限指令

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 注册全局指令 v-permission
app.directive('permission', permissionDirective)

app.mount("#app")
