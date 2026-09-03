/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#ifndef FILELOG_H
#define FILELOG_H

#include "Defines.h"

class FileLog {
public:
    FileLog();
    void init(std::string path);
    static void fatal(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void e(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void w(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void d(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void ref(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void delref(const char *message, ...) __attribute__((format (printf, 1, 2)));

    static FileLog &getInstance();

private:
    FILE *logFile = nullptr;
    // Kept so the log can be restarted in place when it hits its ceiling: there is one file and no
    // rotation, and the interesting part of a stalled session is always the newest part.
    std::string logPath;
    int64_t writtenBytes = 0;
    pthread_mutex_t mutex;
    void onWritten(int bytes);
};

extern bool LOGS_ENABLED;

#define DEBUG_FATAL FileLog::getInstance().fatal
#define DEBUG_E FileLog::getInstance().e
#define DEBUG_W FileLog::getInstance().w
#define DEBUG_D FileLog::getInstance().d

#define DEBUG_REF FileLog::getInstance().ref
#define DEBUG_DELREF FileLog::getInstance().delref

#endif
