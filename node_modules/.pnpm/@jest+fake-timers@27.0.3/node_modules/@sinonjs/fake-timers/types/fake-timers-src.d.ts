export namespace timers {
    const setTimeout: any;
    const clearTimeout: any;
    const setInterval: any;
    const clearInterval: any;
    const Date: any;
}
/**
 * @param start {Date|number} the system time - non-integer values are floored
 * @param loopLimit {number}  maximum number of timers that will be run when calling runAll()
 */
export function createClock(start: Date | number, loopLimit: number): {
    now: number;
    timeouts: {};
    Date: any;
    loopLimit: number;
};
/**
 * Configuration object for the `install` method.
 *
 * @typedef {object} Config
 * @property [now] {number|Date}  a number (in milliseconds) or a Date object (default epoch)
 * @property [toFake] {string[]} names of the methods that should be faked.
 * @property [loopLimit] {number} the maximum number of timers that will be run when calling runAll()
 * @property [shouldAdvanceTime] {Boolean} tells FakeTimers to increment mocked time automatically (default false)
 * @property [advanceTimeDelta] {Number} increment mocked time every <<advanceTimeDelta>> ms (default: 20ms)
 */
/**
 * @param [config] {Config} optional config
 */
export function install(config?: {
    /**
     * a number (in milliseconds) or a Date object (default epoch)
     */
    now?: number | Date;
    /**
     * names of the methods that should be faked.
     */
    toFake?: string[];
    /**
     * the maximum number of timers that will be run when calling runAll()
     */
    loopLimit?: number;
    /**
     * tells FakeTimers to increment mocked time automatically (default false)
     */
    shouldAdvanceTime?: boolean;
    /**
     * increment mocked time every <<advanceTimeDelta>> ms (default: 20ms)
     */
    advanceTimeDelta?: number;
}, ...args: any[]): {
    now: number;
    timeouts: {};
    Date: any;
    loopLimit: number;
};
export function withGlobal(_global: any): {
    timers: {
        setTimeout: any;
        clearTimeout: any;
        setInterval: any;
        clearInterval: any;
        Date: any;
    };
    createClock: (start: Date | number, loopLimit: number) => {
        now: number;
        timeouts: {};
        Date: any;
        loopLimit: number;
    };
    install: (config?: {
        /**
         * a number (in milliseconds) or a Date object (default epoch)
         */
        now?: number | Date;
        /**
         * names of the methods that should be faked.
         */
        toFake?: string[];
        /**
         * the maximum number of timers that will be run when calling runAll()
         */
        loopLimit?: number;
        /**
         * tells FakeTimers to increment mocked time automatically (default false)
         */
        shouldAdvanceTime?: boolean;
        /**
         * increment mocked time every <<advanceTimeDelta>> ms (default: 20ms)
         */
        advanceTimeDelta?: number;
    }, ...args: any[]) => {
        now: number;
        timeouts: {};
        Date: any;
        loopLimit: number;
    };
    withGlobal: typeof withGlobal;
};
