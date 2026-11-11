import { createRouter,createWebHistory } from 'vue-router';
import { restoreSession,session } from './session';
import LoginPage from './pages/LoginPage.vue';
import DashboardPage from './pages/DashboardPage.vue';
export const router=createRouter({history:createWebHistory(),routes:[
  {path:'/login',component:LoginPage,meta:{title:'登录'}},
  {path:'/',redirect:'/dashboard'},
  {path:'/dashboard',component:DashboardPage,meta:{title:'工作台'}},
  {path:'/housing/rooms',component:()=>import('./pages/RoomsPage.vue'),meta:{title:'宿舍档案'}},
  {path:'/housing/students',component:()=>import('./pages/StudentsPage.vue'),meta:{title:'学生入住'}},
  {path:'/housing/residences',redirect:'/housing/students'},
  {path:'/repairs/:id?',component:()=>import('./pages/RepairsPage.vue'),meta:{title:'报修工单'}},
  {path:'/dispatch',component:()=>import('./pages/DispatchPage.vue'),meta:{title:'任务调度'}},
  {path:'/mobility',component:()=>import('./pages/MobilityPage.vue'),meta:{title:'现场位置'}},
  {path:'/hygiene',component:()=>import('./pages/HygienePage.vue'),meta:{title:'卫生巡检'}},
  {path:'/visitors',component:()=>import('./pages/VisitorsPage.vue'),meta:{title:'访客登记'}},
  {path:'/statistics',component:()=>import('./pages/StatsPage.vue'),meta:{title:'数据统计'}},
  {path:'/:pathMatch(.*)*',redirect:'/dashboard'},
]});
router.beforeEach(async to=>{await restoreSession();if(to.path==='/login')return session.user?.role==='MANAGER'?'/dashboard':true;if(session.user?.role!=='MANAGER')return {path:'/login',query:{redirect:to.fullPath}};return true;});
