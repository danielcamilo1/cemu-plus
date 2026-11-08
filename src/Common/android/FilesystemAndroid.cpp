#include "FilesystemAndroid.h"

#include <boost/iostreams/device/file_descriptor.hpp>
#include <boost/iostreams/stream_buffer.hpp>

using namespace boost::iostreams;

namespace FilesystemAndroid
{
std::shared_ptr<FilesystemCallbacks> g_filesystemCallbacks = nullptr;

// DIAGNOSTIC (temporary): time spent in content URI calls, summarized every 2 seconds while there is activity
namespace
{
    enum DiagOp { DIAG_OPEN, DIAG_LIST, DIAG_ISDIR, DIAG_ISFILE, DIAG_EXISTS, DIAG_READ, DIAG_COUNT };
    std::mutex s_diagMtx;
    uint64 s_diagCount[DIAG_COUNT]{};
    uint64 s_diagMicros[DIAG_COUNT]{};
    uint64 s_diagReadBytes = 0;
    std::chrono::steady_clock::time_point s_diagLastReport = std::chrono::steady_clock::now();

    void DiagRecord(DiagOp op, std::chrono::steady_clock::time_point start, const fs::path* uri, uint64 bytes = 0)
    {
        auto now = std::chrono::steady_clock::now();
        uint64 us = std::chrono::duration_cast<std::chrono::microseconds>(now - start).count();
        if (us >= 100000 && uri)
            cemuLog_log(LogType::Force, "DIAG SAF slow call op {} took {}ms: {}", (int)op, us / 1000, _pathToUtf8(*uri));
        std::unique_lock _l(s_diagMtx);
        s_diagCount[op]++;
        s_diagMicros[op] += us;
        s_diagReadBytes += bytes;
        if (now - s_diagLastReport < std::chrono::seconds(2))
            return;
        s_diagLastReport = now;
        cemuLog_log(LogType::Force, "DIAG SAF 2s: open {}/{}ms list {}/{}ms isdir {}/{}ms isfile {}/{}ms exists {}/{}ms read {}/{}ms ({}KB)",
            s_diagCount[DIAG_OPEN], s_diagMicros[DIAG_OPEN] / 1000, s_diagCount[DIAG_LIST], s_diagMicros[DIAG_LIST] / 1000,
            s_diagCount[DIAG_ISDIR], s_diagMicros[DIAG_ISDIR] / 1000, s_diagCount[DIAG_ISFILE], s_diagMicros[DIAG_ISFILE] / 1000,
            s_diagCount[DIAG_EXISTS], s_diagMicros[DIAG_EXISTS] / 1000, s_diagCount[DIAG_READ], s_diagMicros[DIAG_READ] / 1000, s_diagReadBytes / 1024);
        std::fill(std::begin(s_diagCount), std::end(s_diagCount), 0);
        std::fill(std::begin(s_diagMicros), std::end(s_diagMicros), 0);
        s_diagReadBytes = 0;
    }
}

void DiagRecordRead(std::chrono::steady_clock::time_point start, uint64_t bytes)
{
    DiagRecord(DIAG_READ, start, nullptr, bytes);
}

void SetFilesystemCallbacks(std::shared_ptr<FilesystemCallbacks> filesystemCallbacks)
{
    g_filesystemCallbacks = std::move(filesystemCallbacks);
}

int OpenContentUri(const fs::path &uri)
{
    if (g_filesystemCallbacks)
    {
        auto diagStart = std::chrono::steady_clock::now();
        auto r = g_filesystemCallbacks->OpenContentUri(uri);
        DiagRecord(DIAG_OPEN, diagStart, &uri);
        return r;
    }
    return -1;
}

std::vector<fs::path> ListFiles(const fs::path &uri)
{
    if (g_filesystemCallbacks)
    {
        auto diagStart = std::chrono::steady_clock::now();
        auto r = g_filesystemCallbacks->ListFiles(uri);
        DiagRecord(DIAG_LIST, diagStart, &uri);
        return r;
    }
    return {};
}

bool IsDirectory(const fs::path &uri)
{
    if (g_filesystemCallbacks)
    {
        auto diagStart = std::chrono::steady_clock::now();
        auto r = g_filesystemCallbacks->IsDirectory(uri);
        DiagRecord(DIAG_ISDIR, diagStart, &uri);
        return r;
    }
    return false;
}

bool IsFile(const fs::path &uri)
{
    if (g_filesystemCallbacks)
    {
        auto diagStart = std::chrono::steady_clock::now();
        auto r = g_filesystemCallbacks->IsFile(uri);
        DiagRecord(DIAG_ISFILE, diagStart, &uri);
        return r;
    }
    return false;
}

bool Exists(const fs::path &uri)
{
    if (g_filesystemCallbacks)
    {
        auto diagStart = std::chrono::steady_clock::now();
        auto r = g_filesystemCallbacks->Exists(uri);
        DiagRecord(DIAG_EXISTS, diagStart, &uri);
        return r;
    }
    return false;
}

bool IsContentUri(std::string_view uri)
{
    static constexpr auto content = "content://";
    return uri.starts_with(content);
}

}  // namespace FilesystemAndroid