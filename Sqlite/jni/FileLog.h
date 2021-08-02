#ifndef FILELOG_H
#define FILELOG_H

#include "Defines.h"

class FileLog {
public:
    FileLog();
    void init(std::string path);
    static void e(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void w(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void d(const char *message, ...) __attribute__((format (printf, 1, 2)));

    static FileLog &getInstance();

private:
    FILE *logFile = nullptr;
    pthread_mutex_t mutex;
};

extern bool LOGS_ENABLED;

#define DEBUG_E FileLog::getInstance().e
#define DEBUG_W FileLog::getInstance().w
#define DEBUG_D FileLog::getInstance().d

#endif
