#include <signal.h>
#include <execinfo.h>
#include <string.h>
#include <string>
#include "config/CemuConfig.h"
#include "util/helpers/StringHelpers.h"
#include "ExceptionHandler.h"

#include "Cafe/HW/Espresso/Debugger/GDBStub.h"
#include "Cafe/HW/Espresso/Debugger/GDBBreakpoints.h"

#if BOOST_OS_LINUX
#include "ELFSymbolTable.h"
#endif

#if BOOST_PLAT_ANDROID
#include <boost/stacktrace.hpp>
#include <dlfcn.h>

// the handlers that were installed before ours (Android's debuggerd), so a crash still produces a tombstone
static struct sigaction s_previousActions[NSIG];
#endif

#if BOOST_OS_LINUX && !BOOST_PLAT_ANDROID
void DemangleAndPrintBacktrace(char** backtrace, size_t size)
{
	ELFSymbolTable symTable;
	for (char** i = backtrace; i < backtrace + size; i++)
	{
		std::string_view traceLine{*i};

		// basic check to see if the backtrace line matches expected format
		size_t parenthesesOpen = traceLine.find_last_of('(');
		size_t parenthesesClose = traceLine.find_last_of(')');
		size_t offsetPlus = traceLine.find_last_of('+');
		if (!parenthesesOpen || !parenthesesClose || !offsetPlus ||
			 offsetPlus < parenthesesOpen || offsetPlus > parenthesesClose)
		{
			// fall back to default string
            CrashLog_WriteLine(traceLine);
			continue;
		}

		// attempt to resolve symbol from regular symbol table if missing from dynamic symbol table
		uint64 newOffset = -1;
		std::string_view symbolName = traceLine.substr(parenthesesOpen+1, offsetPlus-parenthesesOpen-1);
		if (symbolName.empty())
		{
			uint64 symbolOffset = StringHelpers::ToInt64(traceLine.substr(offsetPlus+1,offsetPlus+1-parenthesesClose-1));
			symbolName = symTable.OffsetToSymbol(symbolOffset, newOffset);
		}

        CrashLog_WriteLine(traceLine.substr(0, parenthesesOpen+1), false);

        CrashLog_WriteLine(boost::core::demangle(symbolName.empty() ? "" : symbolName.data()), false);

		// print relative or existing symbol offset.
        CrashLog_WriteLine("+", false);
		if (newOffset != -1)
		{
            CrashLog_WriteLine(fmt::format("0x{:x}", newOffset), false);
            CrashLog_WriteLine(traceLine.substr(parenthesesClose));
		}
		else
		{
            CrashLog_WriteLine(traceLine.substr(offsetPlus+1));
		}
	}
}
#endif

// handle signals that would dump core, print stacktrace and then dump depending on config
void handlerDumpingSignal(int sig, siginfo_t *info, void *context)
{
#if defined(ARCH_X86_64) && BOOST_OS_LINUX
	// Check for hardware breakpoints
	if (info->si_signo == SIGTRAP && info->si_code == TRAP_HWBKPT)
	{
		uint64 dr6 = _ReadDR6();
		g_gdbstub->HandleAccessException(dr6);
		return;
	}
#endif

    if(!CrashLog_Create())
        return; // give up if crashlog was already created

    char* sigName = strsignal(sig);
	if (sigName)
	{
		printf("%s!\n", sigName);
	}
	else
	{
		// should never be the case
		printf("Unknown core dumping signal!\n");
	}
    CrashLog_WriteLine(fmt::format("Error: signal {}:", sig));
#if BOOST_PLAT_ANDROID
    {
        // the address of the faulting instruction, as library + offset when it's inside a library
#if defined(__aarch64__)
        uint64 pc = ((ucontext_t*)context)->uc_mcontext.pc;
#else
        uint64 pc = ((ucontext_t*)context)->uc_mcontext.gregs[REG_RIP];
#endif
        Dl_info dlInfo{};
        std::string location = "outside of any library (recompiled code?)";
        if (dladdr((void*)pc, &dlInfo) && dlInfo.dli_fname)
            location = fmt::format("{} + 0x{:x}", dlInfo.dli_fname, pc - (uint64)dlInfo.dli_fbase);
        CrashLog_WriteLine(fmt::format("PC 0x{:016x} ({}), fault address 0x{:016x}, code {}", pc, location, (uint64)info->si_addr, info->si_code));
    }
    CrashLog_WriteLine(to_string(boost::stacktrace::stacktrace()));
#else
	void* backtraceArray[128];
	size_t size;

	// get void*'s for all entries on the stack
	size = backtrace(backtraceArray, 128);
    // replace the deepest entry with the actual crash address
#if defined(ARCH_X86_64) && BOOST_OS_LINUX > 0
    ucontext_t *uc = (ucontext_t *)context;
    backtraceArray[0] = (void *)uc->uc_mcontext.gregs[REG_RIP];
#endif

#if BOOST_OS_LINUX
	char** symbol_trace = backtrace_symbols(backtraceArray, size);

	if (symbol_trace)
	{
        DemangleAndPrintBacktrace(symbol_trace, size);
		free(symbol_trace);
	}
	else
	{
        CrashLog_WriteLine("Failed to read backtrace");
	}
#else
	backtrace_symbols_fd(backtraceArray, size, STDERR_FILENO);
#endif
#endif

    std::cerr << fmt::format("\nStacktrace and additional info written to:") << std::endl;
    std::cerr << cemuLog_GetLogFilePath().generic_string() << std::endl;

#if BOOST_PLAT_ANDROID
    // log.txt can't be opened by the user on Android, so the game and PPC info goes to logcat too
    ExceptionHandler_LogGeneralInfo();
    // the log is written by a background thread, let it finish before the process ends
    cemuLog_waitForFlush(std::chrono::milliseconds(500));
    // hand the signal to Android's handler, which writes a tombstone with the native backtrace
    sigaction(sig, &s_previousActions[sig], nullptr);
    if (info->si_code <= 0)
        raise(sig); // sent by abort() or kill, it has to be sent again
    // a fault happens again when the faulting instruction runs again, this time handled by Android
    return;
#endif

    CrashLog_SetOutputChannels(false, true);
    ExceptionHandler_LogGeneralInfo();
    CrashLog_SetOutputChannels(true, true);

	if (GetConfig().crash_dump == CrashDump::Enabled)
	{
		// reset signal handler to default and re-raise signal to dump core
		signal(sig, SIG_DFL);
		raise(sig);
		return;
	}
	// exit process ignoring all issues
	_Exit(1);
}

void handler_SIGINT(int sig)
{
	/*
	 * Received when pressing CTRL + C in a console
	 * Ideally should be exiting cleanly after saving settings but currently
	 * there's no clean exit pathway (at least on linux) and exiting the app
	 * by any mean ends up with a SIGABRT from the standard library destroying
	 * threads.
	 */
	_Exit(0);
}

void ExceptionHandler_Init()
{
	struct sigaction action;
	action.sa_flags = 0;
	sigfillset(&action.sa_mask); // don't allow signals to be interrupted

	action.sa_handler = handler_SIGINT;
	sigaction(SIGINT, &action, nullptr);
	sigaction(SIGTERM, &action, nullptr);

    action.sa_flags = SA_SIGINFO;
    action.sa_handler = nullptr;
	action.sa_sigaction = handlerDumpingSignal;
	for (int sig : {SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGIOT, SIGQUIT, SIGSEGV, SIGSYS, SIGTRAP})
	{
#if BOOST_PLAT_ANDROID
		sigaction(sig, &action, &s_previousActions[sig]);
#else
		sigaction(sig, &action, nullptr);
#endif
	}
}
