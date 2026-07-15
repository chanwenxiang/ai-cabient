"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.init = void 0;
const path_1 = __importDefault(require("path"));
const uni_cli_shared_1 = require("@dcloudio/uni-cli-shared");
const pre_1 = require("./pre");
const plugin_1 = require("./plugin");
const css_1 = require("./css");
const mainUTS_1 = require("./mainUTS");
const manifestJson_1 = require("./manifestJson");
const pagesJson_1 = require("./pagesJson");
const uvue_1 = require("./uvue");
const unicloud_1 = require("./unicloud");
const utils_1 = require("./utils");
function init() {
    return [
        (0, pre_1.uniPrePlugin)(),
        (0, uni_cli_shared_1.uniUTSUniModulesPlugin)({
            x: true,
            isSingleThread: process.env.UNI_APP_X_SINGLE_THREAD !== 'false',
            extApis: (0, uni_cli_shared_1.parseUniExtApiNamespacesOnce)(process.env.UNI_UTS_PLATFORM, process.env.UNI_UTS_TARGET_LANGUAGE),
        }),
        (0, plugin_1.uniAppPlugin)(),
        // 需要放到 uniAppPlugin 之后(TSC模式无需)，让 uniAppPlugin 先 emit 出真实的 main.uts，然后 MainPlugin 再返回仅包含 import 的 js code
        (0, mainUTS_1.uniAppMainPlugin)(),
        (0, manifestJson_1.uniAppManifestPlugin)(),
        (0, pagesJson_1.uniAppPagesPlugin)(),
        (0, css_1.uniAppCssPlugin)(),
        // 解决所有的src引入
        (0, uni_cli_shared_1.uniViteSfcSrcImportPlugin)({ onlyVue: false }),
        (0, uvue_1.uniAppUVuePlugin)(),
        (0, unicloud_1.uniCloudPlugin)(),
        ...(process.env.UNI_APP_X_TSC === 'true'
            ? [
                // 必须在 uvue 处理之后
                (0, uni_cli_shared_1.resolveUTSCompiler)().uts2kotlin({
                    cacheRoot: path_1.default.resolve(process.env.UNI_APP_X_CACHE_DIR ||
                        path_1.default.resolve(process.env.UNI_OUTPUT_DIR, '../.kotlin'), '.uts/cache'),
                    inputDir: process.env.UNI_INPUT_DIR,
                    sourcemap: process.env.NODE_ENV === 'development',
                    fileName(fileName) {
                        const name = (0, utils_1.parseUTSRelativeFilename)(fileName);
                        return name === 'main.uts' ? 'index.uts' : name;
                    },
                    jsCode(code) {
                        return (0, utils_1.parseImports)(code);
                    },
                }),
            ]
            : []),
    ];
}
exports.init = init;
