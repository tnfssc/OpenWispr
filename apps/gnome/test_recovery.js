// Run: node --test test_recovery.js. Exercise the real controller with platform APIs stubbed.
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';
const source = readFileSync(new URL('./extension/extension.js', import.meta.url), 'utf8')
    .replace(/^import .*;$/gm, '').replace('export default class', 'class');
function fixture() {
    const copied = [], pasted = [], states = [], calls = [];
    class Variant {constructor(_type, value) {this.value = value;} deep_unpack() {return this.value;}}
    const context = vm.createContext({console, GLib: {Variant, get_monotonic_time: () => 0},
        Gio: {DBusCallFlags: {NONE: 0}, Cancellable: class {cancel() {}}},
        St: {ClipboardType: {CLIPBOARD: 1}, Clipboard: {get_default: () => ({set_text: (_type, text) => copied.push(text)})}},
        Extension: class {}, _: text => text});
    vm.runInContext(source + '\nglobalThis.Controller = OpenWisprController;', context);
    const c = new context.Controller({uuid: 'test'});
    c._initState(); c._enabled = true;
    c._dbusControl = {emit_signal: (_name, params) => states.push(params.deep_unpack().slice(1))};
    c._notify = () => {}; c._notifyError = message => states.push(['error', '', message]);
    c._injectText = text => pasted.push(text);
    c._setPanelIconState = () => {};
    c._refreshRecovery = () => {};
    c._settings = {}; c._buildCompanionConfig = () => 'current-settings';
    const proxy = {call: (method, args, _flags, _timeout, _cancel, done) => calls.push({method, args, done}),
        call_finish: result => new Variant('', result)};
    c._getCompanionProxy = () => proxy; c._companionProxy = proxy;
    return {c, proxy, calls, copied, pasted, states, Variant};
}
function finishStart(f) {const call = f.calls.find(c => c.method === 'Start'); call.done(f.proxy, [true]);}
function finishStop(f, token = 'test-token') {const call = f.calls.findLast(c => c.method === 'Stop'); call.done(f.proxy, [token]);}

test('test Start is async, Stop shows result without paste or clipboard change', () => {
    const f = fixture();
    assert.equal(f.c.TestStart('session'), true);
    assert.equal(f.c._testActive, true, 'safe routing stays active while Start is pending');
    assert.equal(f.c._toggleRecording(), false, 'normal triggers cannot hijack test');
    finishStart(f);
    assert.equal(f.c._recording, true);
    assert.ok(f.states.some(state => state[0] === 'recording'));
    assert.equal(f.c.TestStop('session'), true); finishStop(f);
    f.c._onCompanionSignal(f.proxy, 'TranscriptionComplete', new f.Variant('', ['test-token', 'A safe result.', '']));
    assert.ok(f.states.some(state => state[0] === 'result' && state[1] === 'A safe result.'));
    assert.deepEqual(f.copied, []); assert.deepEqual(f.pasted, []);
    assert.equal(f.c._testActive, false);
});
test('leave during pending Start explicitly abandons recording when Start replies', () => {
    const f = fixture(); assert.equal(f.c.TestStart('session'), true); assert.equal(f.c.TestCancel('session'), true);
    finishStart(f);
    const stop = f.calls.find(c => c.method === 'Stop');
    assert.equal(stop.args.deep_unpack()[0], false);
    finishStop(f); assert.equal(f.c._testActive, false); assert.deepEqual(f.pasted, []);
    assert.ok(f.states.some(state => state[0] === 'cancelled' && /No audio kept/.test(state[2])));
});
test('Retry takes current settings, handles an early completion and only copies', () => {
    const f = fixture(); f.c._retrySavedAudio();
    const retry = f.calls.find(c => c.method === 'Retry');
    assert.equal(retry.args.deep_unpack()[0], 'current-settings');
    f.c._onCompanionSignal(f.proxy, 'TranscriptionComplete', new f.Variant('', ['retry-token', 'Recovered words.', '']));
    retry.done(f.proxy, ['retry-token']);
    assert.deepEqual(f.copied, ['Recovered words.']); assert.deepEqual(f.pasted, []);
    assert.equal(f.c._processing, false);
});
test('cancelled Retry never copies a late result and keeps the cancel token', () => {
    const f = fixture(); f.c._retrySavedAudio(); f.c._cancelTranscription();
    const retry = f.calls.find(c => c.method === 'Retry'); retry.done(f.proxy, ['retry-token']);
    assert.ok(f.calls.some(c => c.method === 'Cancel' && c.args.deep_unpack()[0] === 'retry-token'));
    f.c._handleTranscriptionComplete('retry-token', 'Late text', '');
    assert.deepEqual(f.copied, []); assert.deepEqual(f.pasted, []);
});
test('network failure tells the user audio is saved and gives connection/recovery actions', () => {
    const f = fixture(); f.c._retrySavedAudio(); f.calls[0].done(f.proxy, ['retry-token']);
    f.c._handleTranscriptionComplete('retry-token', '', 'HTTP request: network unavailable');
    const error = f.states.find(state => state[0] === 'error')[2];
    assert.match(error, /connection/); assert.match(error, /saved on this device/); assert.match(error, /Retry or Discard/);
    assert.deepEqual(f.pasted, []);
});
test('busy discard does not issue a destructive D-Bus call', () => {
    const f = fixture(); f.c._processing = true; f.c._discardSavedAudio();
    assert.equal(f.calls.length, 0);
});

test('a stale preferences window cannot stop or cancel a newer safe test', () => {
 const f = fixture(); f.c.TestStart('new-session'); finishStart(f);
 assert.equal(f.c.TestStop('old-session'), false); assert.equal(f.c.TestCancel('old-session'), false);
 assert.equal(f.calls.length, 1); assert.equal(f.c._testActive, true);
});

for (const count of [0, 2]) {
    test(`recovery controls only appear for saved audio: count ${count}`, () => {
        const f = fixture();
        const item = () => ({visible: false, label: {text: ''}, setSensitive(value) {this.sensitive = value;}});
        f.c._recordItem = item(); f.c._cancelItem = item(); f.c._savedItem = item();
        f.c._retryItem = item(); f.c._discardItem = item();
        Object.getPrototypeOf(f.c)._refreshRecovery.call(f.c);
        const status = f.calls.find(call => call.method === 'RecoveryStatus');
        status.done(f.proxy, [count]);
        for (const name of ['_savedItem', '_retryItem', '_discardItem']) {
            assert.equal(f.c[name].visible, count > 0, name);
        }
        f.c._processing = true;
        Object.getPrototypeOf(f.c)._refreshRecovery.call(f.c);
        f.calls.findLast(call => call.method === 'RecoveryStatus').done(f.proxy, [count]);
        assert.equal(f.c._retryItem.visible, false, 'No recovery controls during current processing');
        assert.equal(f.c._discardItem.visible, false);
    });
}
