import { createRouter, createWebHistory } from "vue-router"
import { ElMessage } from "element-plus"
import { checkRouteAccess, getDeptCode, getRole, getToken, isSuperAdmin } from "@/utils/auth"
import { WARNING_DEPT_CODES } from "@/utils/constants"

// D77/ADR-0009：超管 = 治理/审计角色——开放全部业务模块页面只读进入（按钮由 v-permission 禁用），
// 财务模块（销售统计/年度经营统计）与员工分布图表仍明确排除；业务写接口由后端统一 403 兜底。
const SUPERADMIN_ALLOWED_PATHS = new Set([
  '/',
  '/home',
  '/system/notice',
  '/system/super-admin',
  '/system/dept-approval',
  '/system/void-approval',
  '/system/config',
  '/system/user',
  '/system/security-ip-policy',
  '/system/login-log',
  '/system/operation-log',
  '/assistant/project',
  '/403',
  // 基础资料
  '/base/supplier',
  '/base/goods',
  '/base/products',
  '/base/bom',
  // 业务单据
  '/business/purchase',
  '/business/purchase-return',
  '/business/sales',
  '/business/sales-return',
  '/business/purchase-request',
  '/business/production-order',
  '/business/qc',
  '/business/production',
  '/business/pick-list',
  '/business/stock-warning',
  // 人事档案 + 工作要求（只读）
  '/system/dept',
  '/system/employee',
  '/system/work-requirement'
])

// 动态详情路由白名单（前缀匹配）：工作要求详情
const SUPERADMIN_ALLOWED_PREFIXES = ['/work-requirement/']

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "Login",
      component: () => import("../views/LoginView.vue")
    },
    {
      path: "/register",
      name: "Register",
      component: () => import("../views/RegisterView.vue")
    },
    {
      path: "/",
      component: () => import("../layout/index.vue"),
      redirect: "/home",
      children: [
        {
          path: "home",
          name: "Home",
          component: () => import("../views/HomeView.vue")
        },
        // 基础资料页面 (由于普通员工只能查看，故不使用角色锁，在页面中利用 v-permission 控制操作按钮即可)
        {
          path: "base/supplier",
          name: "BaseSupplier",
          component: () => import("../views/base/SupplierView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['purchase'] }
        },
        {
          path: "base/goods",
          name: "BaseGoods",
          component: () => import("../views/base/GoodsView.vue"),
          // D68：销售部门放开只读（售价维护按部门分支控制，同采购进价范式）
          meta: { roles: ['admin', 'employee'], deptCodes: ['warehouse', 'purchase', 'production', 'sales'] }
        },
        // D65 主数据分域：成品管理与物料管理同组件分页渲染，权限沿用物料管理
        {
          path: "base/products",
          name: "BaseProducts",
          component: () => import("../views/base/GoodsView.vue"),
          props: { goodsType: "product" },
          meta: { roles: ['admin', 'employee'], deptCodes: ['warehouse', 'purchase', 'production', 'sales'] }
        },
        {
          path: "base/bom",
          name: "BaseBom",
          component: () => import("../views/base/BomView.vue"),
          meta: { roles: ['admin', 'employee', 'superadmin'], deptCodes: ['production', 'warehouse'] }
        },
        {
          path: "business/production-order",
          name: "BusinessProductionOrder",
          component: () => import("../views/business/ProductionOrderView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['production'] }
        },
        {
          path: "business/qc",
          name: "BusinessQc",
          component: () => import("../views/business/QcView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['production'] }
        },
        {
          path: "business/purchase",
          name: "BusinessPurchase",
          component: () => import("../views/business/PurchaseView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['purchase', 'warehouse'] }
        },
        {
          path: "business/purchase-return",
          name: "BusinessPurchaseReturn",
          component: () => import("../views/business/PurchaseReturnView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['purchase', 'warehouse'] }
        },
        {
          path: "business/sales",
          name: "BusinessSales",
          component: () => import("../views/business/SalesView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['sales', 'warehouse'] }
        },
        {
          path: "business/sales-return",
          name: "BusinessSalesReturn",
          component: () => import("../views/business/SalesReturnView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['sales', 'warehouse'] }
        },
        {
          path: "business/sales-chart",
          name: "BusinessSalesChart",
          component: () => import("../views/business/SalesChartView.vue"),
          meta: { roles: ['admin'], deptCodes: ['finance'] }
        },
        {
          path: "business/annual-stats",
          name: "BusinessAnnualStats",
          component: () => import("../views/business/AnnualStatsView.vue"),
          meta: { roles: ['admin'], deptCodes: ['finance'] }
        },
        {
          path: "business/stock-warning",
          name: "BusinessStockWarning",
          component: () => import("../views/business/StockWarningView.vue"),
          meta: { roles: ['admin'], deptCodes: WARNING_DEPT_CODES }
        },
        {
          path: "business/pick-list",
          name: "BusinessPickList",
          component: () => import("../views/business/PickListView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['warehouse', 'production'] }
        },
        {
          path: "business/purchase-request",
          name: "BusinessPurchaseRequest",
          component: () => import("../views/business/PurchaseRequestView.vue"),
          meta: { roles: ['admin'], deptCodes: ['warehouse', 'purchase'] }
        },
        {
          path: "business/production",
          name: "BusinessProduction",
          component: () => import("../views/business/ProductionView.vue"),
          meta: { roles: ['admin', 'employee'], deptCodes: ['warehouse', 'production'] }
        },
        {
          path: "system/notice",
          name: "SystemNotice",
          component: () => import("../views/system/NoticeView.vue"),
          meta: { roles: ['admin', 'superadmin'] }
        },
        {
          path: "system/user",
          name: "SystemUser",
          component: () => import("../views/system/UserView.vue"),
          meta: { roles: ['admin', 'superadmin'] }
        },
        {
          path: "system/dept",
          name: "SystemDept",
          component: () => import("../views/system/DeptView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/employee",
          name: "SystemEmployee",
          component: () => import("../views/system/EmployeeView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/hr-chart",
          name: "SystemHrChart",
          component: () => import("../views/system/HrChartView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/void-approval",
          name: "SystemVoidApproval",
          component: () => import("../views/system/VoidApprovalView.vue"),
          meta: { roles: ['admin', 'superadmin'], deptCodes: ['warehouse'] }
        },
        {
          path: "system/config",
          name: "SystemConfig",
          component: () => import("../views/system/SystemConfigView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/super-admin",
          name: "SystemSuperAdmin",
          component: () => import("../views/system/SuperAdminDashboardView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/dept-approval",
          name: "SystemDeptApproval",
          component: () => import("../views/system/DeptApprovalView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/security-ip-policy",
          name: "SystemSecurityIpPolicy",
          component: () => import("../views/system/SecurityIpPolicyView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/login-log",
          name: "SystemLoginLog",
          component: () => import("../views/system/LoginLogView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/operation-log",
          name: "SystemOperationLog",
          component: () => import("../views/system/OperationLogView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/work-requirement",
          name: "SystemWorkRequirement",
          component: () => import("../views/system/WorkRequirementView.vue"),
          meta: { roles: ['admin'] }
        },
        {
          path: "work-requirement/:assignId",
          name: "WorkRequirementDetail",
          component: () => import("../views/WorkRequirementDetailView.vue"),
          meta: { roles: ['employee', 'admin'] }
        },
        {
          path: "assistant/project",
          name: "ProjectAssistant",
          component: () => import("../views/system/ProjectAssistantView.vue"),
          meta: { roles: ['superadmin', 'admin', 'employee'] }
        }
      ]
    },
    // 将无权限页面重定向
    {
      path: "/403",
      name: "Forbidden",
      component: () => import("../views/ForbiddenView.vue")
    }
  ]
})

// 添加前置路由全局守卫：处理登录拦截与角色权限
router.beforeEach((to, from, next) => {
  const token = getToken()
  const role = getRole()
  const deptCode = getDeptCode()

  if (to.path !== '/login' && to.path !== '/register') {
    if (!token) {
      return next('/login')
    } else {
      if (isSuperAdmin(role)
        && !SUPERADMIN_ALLOWED_PATHS.has(to.path)
        && !SUPERADMIN_ALLOWED_PREFIXES.some((prefix) => to.path.startsWith(prefix))) {
        ElMessage.warning('超级管理员仅开放首页、超管中心与业务模块只读查看')
        return next('/home')
      }

      const { ok, reason } = checkRouteAccess(to.meta || {}, role, deptCode)
      if (!ok) {
        ElMessage.error(reason === 'role' ? '无权限访问该页面' : '当前部门无权限访问该页面')
        return next('/403')
      }
      return next()
    }
  } else {
    next()
  }
})

export default router