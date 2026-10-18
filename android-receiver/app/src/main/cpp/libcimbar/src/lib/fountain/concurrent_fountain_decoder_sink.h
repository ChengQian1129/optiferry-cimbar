/* This code is subject to the terms of the Mozilla Public License, v.2.0. http://mozilla.org/MPL/2.0/. */
#pragma once

#include "fountain_decoder_sink.h"

#include "concurrentqueue/concurrentqueue.h"
#include <mutex>

class concurrent_fountain_decoder_sink
{
public:
	concurrent_fountain_decoder_sink(unsigned chunk_size, const std::function<std::string(const std::string&, const std::vector<uint8_t>&)>& on_store=nullptr)
		: _decoder(chunk_size, on_store)
	{
	}

	bool good() const
	{
		return true;
	}

	unsigned chunk_size() const
	{
		return _decoder.chunk_size();
	}

	unsigned num_streams() const
	{
		std::lock_guard<std::mutex> lock(_readMutex);
		return _numStreams;
	}

	unsigned num_done() const
	{
		std::lock_guard<std::mutex> lock(_readMutex);
		return _numDone;
	}

	std::vector<std::string> get_done() const
	{
		std::lock_guard<std::mutex> lock(_readMutex);
		return _done;
	}

	std::vector<double> get_progress() const
	{
		std::lock_guard<std::mutex> lock(_readMutex);
		return _progress;
	}

	void update_status()
	{
		// we call this under the writeMutex+readMutex. The `const`s are only under readMutex.
		// just thought you ought to know
		std::lock_guard<std::mutex> lock(_readMutex);
		_done = _decoder.get_done();
		_progress = _decoder.get_progress();
		_numStreams = _decoder.num_streams();
		_numDone = _decoder.num_done();
	}

	void process()
	{
		std::unique_lock<std::mutex> lock(_writeMutex, std::try_to_lock);
		if (!lock.owns_lock())
			return;

		std::string buff;
		while (_backlog.try_dequeue(buff))
			_decoder << buff;

		update_status();
	}

	bool write(const char* data, unsigned length)
	{
		std::string buffer(data, length);
		operator<<(buffer);
		return true;
	}

	concurrent_fountain_decoder_sink& operator<<(const std::string& buffer)
	{
		_backlog.enqueue(buffer);
		process();
		return *this;
	}

protected:
	std::mutex _writeMutex;
	mutable std::mutex _readMutex;
	fountain_decoder_sink _decoder;
	moodycamel::ConcurrentQueue< std::string > _backlog;

	std::vector<std::string> _done;
	std::vector<double> _progress;
	unsigned _numStreams = 0;
	unsigned _numDone = 0;
};
