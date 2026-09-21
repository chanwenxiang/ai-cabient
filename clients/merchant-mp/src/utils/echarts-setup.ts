/**
 * ECharts **按需注册**（进程内执行一次）。
 *
 * ⚠️ 微信小程序主包有体积上限，**禁止** `import * as echarts from 'echarts'` 全量引入
 * （全量 dist 约 1MB）；这里只注册构成图用到的渲染器与组件，新增图表类型时按需追加。
 */
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

export { echarts };
