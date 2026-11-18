// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <mutex>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;

	// Kept so the stream can be rebuilt after a route change (headphones
	// plugged in or pulled out) without asking the session for new settings.
	int32_t channel_count = 0;
	int32_t sample_rate = 0;
	std::mutex stream_mutex;
	// Guards against a reopen loop if the new stream keeps failing immediately.
	std::atomic<int> reopen_attempts{0};
	// Set while the output is being torn down so a late error callback does not
	// resurrect the stream during shutdown.
	std::atomic<bool> stopping{false};

	AudioOutput() : stream_callback(this) {}

	oboe::Result Open(bool exclusive);
	void Reopen();
};

// Opens and starts a stream with the stored channel count and sample rate.
oboe::Result AudioOutput::Open(bool exclusive)
{
	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(channel_count)
		->setSampleRate(sample_rate)
		->setCallback(&stream_callback);

	stream.reset();

	auto result = builder.openManagedStream(stream);
	if(result != oboe::Result::OK)
		return result;

	return stream->start();
}

// Called by Oboe after the stream was closed because of an error, which is what
// happens when the audio route changes (wired headset plugged or unplugged,
// Bluetooth connect, device switch). Without reopening, the session stays silent
// for the rest of the stream.
void AudioOutput::Reopen()
{
	std::lock_guard<std::mutex> lock(stream_mutex);

	if(stopping.load())
		return;

	if(channel_count <= 0 || sample_rate <= 0)
	{
		CHIAKI_LOGE(log, "Audio Output cannot reopen: no settings recorded yet");
		return;
	}

	if(reopen_attempts.fetch_add(1) >= 8)
	{
		CHIAKI_LOGE(log, "Audio Output giving up after repeated reopen failures");
		return;
	}

	auto result = Open(true);
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGW(log, "Audio Output reopen (exclusive) failed: %s, retrying shared",
			oboe::convertToText(result));
		result = Open(false);
	}

	if(result == oboe::Result::OK)
		CHIAKI_LOGI(log, "Audio Output reopened after device change");
	else
		CHIAKI_LOGE(log, "Audio Output failed to reopen: %s", oboe::convertToText(result));
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stopping.store(true);
	{
		std::lock_guard<std::mutex> lock(ao->stream_mutex);
		ao->stream = nullptr;
	}
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	ao->channel_count = static_cast<int32_t>(channels);
	ao->sample_rate = static_cast<int32_t>(rate);

	auto result = ao->Open(true);
	if(result == oboe::Result::OK)
	{
		CHIAKI_LOGI(ao->log, "Audio Output opened and started Oboe stream");
		return;
	}

	CHIAKI_LOGW(ao->log, "Audio Output exclusive stream failed: %s, retrying shared",
		oboe::convertToText(result));
	result = ao->Open(false);
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output opened and started shared Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s",
			oboe::convertToText(result));
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	audio_output->reopen_attempts.store(0, std::memory_order_relaxed);

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	// A route change shows up here with the old stream already closed. Rebuild so
	// audio keeps flowing to whatever device is active now.
	CHIAKI_LOGI(audio_output->log, "Oboe closed the stream after an error (%s), reopening",
		oboe::convertToText(error));
	audio_output->Reopen();
}
