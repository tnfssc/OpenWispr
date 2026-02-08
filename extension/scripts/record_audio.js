import Gst from 'gi://Gst';
import GLib from 'gi://GLib';
import system from 'system';

// Initialize GStreamer
Gst.init(null);

const loop = new GLib.MainLoop(null, false);

const outputFile = system.programArgs[0] || 'test_output.wav';

// Create the pipeline
// autoaudiosrc: Automatically detects the default input (mic)
// audioconvert: Converts raw audio to a format the encoder handles
// wavenc: Encodes to WAV format
// filesink: Writes to a file
const pipelineDescription = `autoaudiosrc ! audioconvert ! wavenc ! filesink location=${outputFile}`;
const pipeline = Gst.parse_launch(pipelineDescription);

// Bus for message handling (errors, EOS)
const bus = pipeline.get_bus();
bus.add_signal_watch();
bus.connect('message', (bus, message) => {
    switch (message.type) {
        case Gst.MessageType.EOS:
            print('End of stream');
            loop.quit();
            break;
        case Gst.MessageType.ERROR:
            const [err, debug] = message.parse_error();
            print(`Error: ${err.message}`);
            print(`Debug info: ${debug}`);
            loop.quit();
            break;
    }
});

// Start recording
print('Starting recording... (Press Ctrl+C to stop)');
pipeline.set_state(Gst.State.PLAYING);

// Handle SIGINT (Ctrl+C) to stop cleanly
const SIGINT = 2;
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, SIGINT, () => {
    print('\nStopping recording...');
    pipeline.send_event(Gst.Event.new_eos());
    return GLib.SOURCE_REMOVE; // Stop listening for this signal
});

// Run the main loop
loop.run();

// Clean up
pipeline.set_state(Gst.State.NULL);
print(`Recording saved to ${outputFile}`);
