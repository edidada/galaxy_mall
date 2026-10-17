import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('./views/HomeView.vue'), meta: { title: '首页' } },
  { path: '/products', name: 'product-list', component: () => import('./views/ProductListView.vue'), meta: { title: '商品列表' } },
  { path: '/products/:productId', name: 'product-detail', component: () => import('./views/ProductDetailView.vue'), meta: { title: '商品详情' }, props: true },
  { path: '/cart', name: 'cart', component: () => import('./views/CartView.vue'), meta: { title: '购物车' } },
  { path: '/checkout', name: 'checkout', component: () => import('./views/CheckoutView.vue'), meta: { title: '订单确认' }, props: true },
  { path: '/pay/:orderId', name: 'pay', component: () => import('./views/PayView.vue'), meta: { title: '收银台' }, props: true },
  { path: '/orders', name: 'my-orders', component: () => import('./views/MyOrdersView.vue'), meta: { title: '我的订单' } },
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { title: '登录' } },
  { path: '/track', name: 'track', component: () => import('./views/TrackView.vue'), meta: { title: '埋点看板' } },
  { path: '/console', name: 'api-console', component: () => import('./views/ApiConsoleView.vue'), meta: { title: 'API 测试台' } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('./views/NotFoundView.vue'), meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (to, from) => (to.path === from?.path ? false : { top: 0 })
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '商品'} · Galaxy Mall`;
});

export default router
