#pragma once

#include "compression/zstd_decompressor.h"
#include "encoder/Decoder.h"
#include "extractor/Anchor.h"
#include "extractor/Deskewer.h"
#include "extractor/Extractor.h"
#include "extractor/Scanner.h"
#include "fountain/concurrent_fountain_decoder_sink.h"

#include "concurrent/thread_pool.h"
#include <opencv2/opencv.hpp>
#include <atomic>
#include <fstream>
#include <filesystem>

namespace {

inline std::atomic<unsigned long> gAtomicStoreSequence{0};

// CFC normally writes directly to the final filename.  A repeated transfer
// can produce the same name while the Kotlin store is still validating the
// previous file, so finish the decompression in a private temporary file and
// publish it with one rename.
inline std::function<std::string(const std::string&, const std::vector<uint8_t>&)>
atomic_decompress_on_store(const std::string& data_path)
{
	return [data_path](const std::string& fallback_name, const std::vector<uint8_t>& data)
	{
		std::string filename = cimbar::zstd_header_check::get_filename(data.data(), data.size());
		if (!filename.empty())
			filename = File::basename(filename);
		if (filename.empty())
			filename = fallback_name;
		if (filename.empty() || filename == "." || filename == ".." ||
			filename.find('/') != std::string::npos || filename.find('\\') != std::string::npos)
			return std::string{};
		for (unsigned char character : filename)
			if (character < 0x20 || character == 0x7f)
				return std::string{};

		const std::filesystem::path final_path = std::filesystem::path(data_path) / filename;
		const auto sequence = gAtomicStoreSequence.fetch_add(1, std::memory_order_relaxed);
		const std::filesystem::path temporary_path =
			final_path.string() + ".tmp-" + std::to_string(sequence);
		{
			cimbar::zstd_decompressor<std::ofstream> output(temporary_path.string(), std::ios::binary);
			output.write(reinterpret_cast<const char*>(data.data()), data.size());
			output.flush();
			if (!output.good() || !output.finish())
			{
				std::error_code ignored;
				std::filesystem::remove(temporary_path, ignored);
				return std::string{};
			}
		}

		std::error_code rename_error;
		std::filesystem::rename(temporary_path, final_path, rename_error);
		if (rename_error)
		{
			// Android uses POSIX rename semantics. Keep a conservative fallback
			// for unusual providers/filesystems so a transient rename failure does
			// not silently mark a decoded stream as complete with no output.
			std::error_code copy_error;
			std::filesystem::copy_file(temporary_path, final_path,
				std::filesystem::copy_options::overwrite_existing, copy_error);
			std::error_code ignored;
			std::filesystem::remove(temporary_path, ignored);
			if (copy_error)
				return std::string{};
		}
		return filename;
	};
}

}  // namespace

class MultiThreadedDecoder
{
public:
	MultiThreadedDecoder(std::string data_path, int mode_val);

	inline static std::atomic<clock_t> count{0};
	inline static std::atomic<clock_t> bytes{0};
	inline static std::atomic<clock_t> perfect{0};
	inline static std::atomic<clock_t> decoded{0};
	inline static std::atomic<clock_t> decodeTicks{0};
	inline static std::atomic<clock_t> scanned{0};
	inline static std::atomic<clock_t> scanTicks{0};
	inline static std::atomic<clock_t> extractTicks{0};

	bool add(cv::Mat mat);

	void stop();

	int mode() const;
	bool set_mode(int mode_val);
	int detected_mode() const;

	unsigned num_threads() const;
	unsigned backlog() const;
	unsigned files_in_flight() const;
	unsigned files_decoded() const;
	std::vector<std::string> get_done() const;
	std::vector<double> get_progress() const;

protected:
	int do_extract(const cv::Mat& mat, cv::Mat& img);
	void save(const cv::Mat& img);

	static unsigned fountain_chunk_size(int mode_val);

protected:
	std::atomic<int> _modeVal;
	std::atomic<int> _detectedMode;

	Decoder _dec;
	unsigned _numThreads;
	turbo::thread_pool _pool;
	concurrent_fountain_decoder_sink _writer;
	std::string _dataPath;
	unsigned _successCondition;
	std::atomic<unsigned> _submittedFrames{0};
};

inline MultiThreadedDecoder::MultiThreadedDecoder(std::string data_path, int mode_val)
	: _modeVal(mode_val)
	, _detectedMode(0)
	, _dec(cimbar::Config::ecc_bytes(), cimbar::Config::color_bits())
	, _numThreads(std::max<int>(((int)std::thread::hardware_concurrency()/2), 1))
	, _pool(_numThreads, 1)
	, _writer(fountain_chunk_size(mode_val), atomic_decompress_on_store(data_path))
	, _dataPath(data_path)
	, _successCondition(cimbar::Config::temp_conf(mode_val).capacity() * .7)
{
	FountainInit::init();
	_pool.start();
}

inline int MultiThreadedDecoder::do_extract(const cv::Mat& mat, cv::Mat& img)
{
	clock_t begin = clock();

	Scanner scanner(mat);
	std::vector<Anchor> anchors = scanner.scan();
	++scanned;
	scanTicks += (clock() - begin);

	//if (anchors.size() >= 3) save(mat);

	if (anchors.size() < 4)
		return Extractor::FAILURE;

	begin = clock();
	Corners corners(anchors);
	Deskewer de;
	img = de.deskew(mat, corners);
	extractTicks += (clock() - begin);

	return Extractor::SUCCESS;
}

inline bool MultiThreadedDecoder::add(cv::Mat mat)
{
    ++count;
    // CameraX can deliver frames faster than extraction and fountain decoding
    // can finish. Keep only a short backlog so an earlier stream cannot delay
    // the next input file by minutes after the sender has already switched.
    if (_pool.queued() >= _numThreads * 2u)
        return false;

    unsigned modeVal = _modeVal;
    if (modeVal == 0)
    {
        switch (_submittedFrames.fetch_add(1, std::memory_order_relaxed)%4) {
            case 1:
                modeVal = 4;
                break;
            case 2:
                modeVal = 66;
                break;
            case 3:
                modeVal = 67;
                break;
            default:
                modeVal = 68;
        }
    }
	return _pool.try_execute( [this, mat, modeVal] () {
		try
		{
			cimbar::Config::update(modeVal);
			cv::Mat img;
			int res = do_extract(mat, img);
			if (res == Extractor::FAILURE)
				return;

			// if extracted image is small, we'll need to run some filters on it
			clock_t begin = clock();
			bool should_preprocess = (res == Extractor::NEEDS_SHARPEN);
			int color_correction = modeVal==4? 1 : 2;
			unsigned decodeRes = _dec.decode_fountain(img, _writer, should_preprocess, color_correction);
			bytes += decodeRes;
			++decoded;
			decodeTicks += clock() - begin;

			if (decodeRes and _modeVal == 0)
				_detectedMode = modeVal;

			if (decodeRes >= _successCondition)
				++perfect;
		}
		catch (...)
		{
			// A malformed camera frame must be dropped without terminating the
			// thread pool or the Android process.
		}
	} );
}

inline void MultiThreadedDecoder::save(const cv::Mat& mat)
{
	std::stringstream fname;
	fname << _dataPath << "/scan" << (scanned-1) << ".png";
	cv::Mat bgr;
	cv::cvtColor(mat, bgr, cv::COLOR_RGB2BGR);
	cv::imwrite(fname.str(), bgr);
}

inline void MultiThreadedDecoder::stop()
{
	_pool.stop();
}

unsigned MultiThreadedDecoder::fountain_chunk_size(int mode_val)
{
	return cimbar::Config::temp_conf(mode_val).fountain_chunk_size();
}

inline int MultiThreadedDecoder::mode() const
{
	return _modeVal;
}

inline bool MultiThreadedDecoder::set_mode(int mode_val)
{
	if (_modeVal == mode_val)
		return true;

	if (mode_val != 0 and _writer.chunk_size() != fountain_chunk_size(mode_val))
		return false; // if so, we need to reset to change it

	// reset detectedMode iff we're switching back to autodetect
	if (mode_val == 0)
		_detectedMode = 0;

	_modeVal = mode_val;
	return true;
}

inline int MultiThreadedDecoder::detected_mode() const
{
	return _detectedMode;
}

inline unsigned MultiThreadedDecoder::num_threads() const
{
	return _numThreads;
}

inline unsigned MultiThreadedDecoder::backlog() const
{
	return _pool.queued();
}

inline unsigned MultiThreadedDecoder::files_in_flight() const
{
	return _writer.num_streams();
}

inline unsigned MultiThreadedDecoder::files_decoded() const
{
	return _writer.num_done();
}

inline std::vector<std::string> MultiThreadedDecoder::get_done() const
{
	return _writer.get_done();
}

inline std::vector<double> MultiThreadedDecoder::get_progress() const
{
	return _writer.get_progress();
}
