#pragma once

class FileStreamSAF
{
  public:
	static FileStreamSAF* OpenFile(const fs::path& path);

	// size and seek
	void SetPosition(uint64 pos);

	uint64 GetSize();
	bool SetEndOfFile();
	void extract(std::vector<uint8>& data);

	// reading
	uint32 readData(void* data, uint32 length);
	bool readU64(uint64& v);
	bool readU32(uint32& v);
	bool readU8(uint8& v);
	bool readLine(std::string& line);

	// writing (not supported)
	sint32 writeData(const void* data, sint32 length);
	void writeU64(uint64 v);
	void writeU32(uint32 v);
	void writeU8(uint8 v);
	void writeStringFmt(const char* format, ...);
	void writeString(const char* str);
	void writeLine(const char* str);
	void Flush();

	~FileStreamSAF();

  private:
	FileStreamSAF(const fs::path& uri);
	// reads go straight to the file descriptor with pread() at m_position
	// a std::istream on the fd (used before) gets stuck in its fail state after an interrupted or failed read, and every later read then returns 0 bytes without the caller noticing
	int m_fd = -1;
	uint64 m_position = 0;
	bool m_isValid = false;
};
