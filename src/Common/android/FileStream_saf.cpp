#include "FileStream_saf.h"
#include "FilesystemAndroid.h"

#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

FileStreamSAF* FileStreamSAF::OpenFile(const fs::path& path)
{
	auto fileStream = new FileStreamSAF(path);
	if (fileStream->m_isValid)
	{
		return fileStream;
	}

	delete fileStream;
	return nullptr;
}

void FileStreamSAF::SetPosition(uint64 pos)
{
	if (!m_isValid)
		return;
	m_position = pos;
}

uint64 FileStreamSAF::GetSize()
{
	cemu_assert(m_isValid);
	struct stat st{};
	if (fstat(m_fd, &st) == 0 && S_ISREG(st.st_mode))
		return (uint64)st.st_size;
	off64_t end = lseek64(m_fd, 0, SEEK_END);
	return end < 0 ? 0 : (uint64)end;
}

bool FileStreamSAF::SetEndOfFile()
{
	return true;
}

void FileStreamSAF::extract(std::vector<uint8>& data)
{
	if (!m_isValid)
		return;
	uint64 fileSize = GetSize();
	SetPosition(0);
	data.resize(fileSize);
	readData(data.data(), fileSize);
}

uint32 FileStreamSAF::readData(void* data, uint32 length)
{
	if (!m_isValid)
		return 0;
	uint8* out = (uint8*)data;
	uint32 totalRead = 0;
	while (totalRead < length)
	{
		ssize_t r = pread64(m_fd, out + totalRead, length - totalRead, (off64_t)(m_position + totalRead));
		if (r < 0)
		{
			if (errno == EINTR)
				continue;
			cemuLog_log(LogType::Force, "FileStreamSAF: read of 0x{:x} bytes at 0x{:x} failed after 0x{:x} bytes (errno {})", length, m_position, totalRead, errno);
			break;
		}
		if (r == 0)
			break; // end of file
		totalRead += (uint32)r;
	}
	m_position += totalRead;
	return totalRead;
}

bool FileStreamSAF::readU64(uint64& v)
{
	return readData(&v, sizeof(uint64)) == sizeof(uint64);
}

bool FileStreamSAF::readU32(uint32& v)
{
	return readData(&v, sizeof(uint32)) == sizeof(uint32);
}

bool FileStreamSAF::readU8(uint8& v)
{
	return readData(&v, sizeof(uint8)) == sizeof(uint8);
}

bool FileStreamSAF::readLine(std::string& line)
{
	line.clear();
	uint8 c;
	bool isEOF = true;
	while (readU8(c))
	{
		isEOF = false;
		if (c == '\r')
			continue;
		if (c == '\n')
			break;
		line.push_back((char)c);
	}
	return !isEOF;
}

sint32 FileStreamSAF::writeData(const void* data, sint32 length)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeU64(uint64 v)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeU32(uint32 v)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeU8(uint8 v)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeStringFmt(const char* format, ...)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeString(const char* str)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::writeLine(const char* str)
{
	throw std::runtime_error("write operation not supported");
}

void FileStreamSAF::Flush()
{
	throw std::runtime_error("write operation not supported");
}

FileStreamSAF::FileStreamSAF(const fs::path& uri) : m_fd(FilesystemAndroid::OpenContentUri(uri))
{
	m_isValid = m_fd >= 0;
}

FileStreamSAF::~FileStreamSAF()
{
	if (m_fd >= 0)
		close(m_fd);
}
