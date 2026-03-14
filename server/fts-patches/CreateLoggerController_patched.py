from FreeTAKServer.core.configuration.LoggingConstants import LoggingConstants
from FreeTAKServer.core.configuration.MainConfig import MainConfig
from logging.handlers import RotatingFileHandler
import logging
import os
import sys

config = MainConfig.instance()

loggingConstants = LoggingConstants()
class CreateLoggerController:
    def __init__(self, loggername, logging_constants=loggingConstants):
        self.logger = logging.getLogger(loggername)
        self.logger.propagate = True
        log_format = logging.Formatter(logging_constants.LOGFORMAT)

        # PATCHED: initialize log_level to WARNING as default so it is always
        # bound — FTS_LOG_LEVEL values other than info/error/debug (e.g. "warning")
        # previously left log_level unset, crashing on Python 3.12+
        log_level = logging.WARNING

        if config.LogLevel.lower() == "info":
            log_level = logging.INFO
            self.logger.addHandler(self.newHandler(logging_constants.INFOLOG, log_level, log_format, logging_constants))
        elif config.LogLevel.lower() == "warning":
            log_level = logging.WARNING
        elif config.LogLevel.lower() == "error":
            log_level = logging.ERROR
            self.logger.addHandler(self.newHandler(logging_constants.ERRORLOG, log_level, log_format, logging_constants))
        elif config.LogLevel.lower() == "debug":
            log_level = logging.DEBUG
            self.logger.addHandler(self.newHandler(logging_constants.DEBUGLOG, log_level, log_format, logging_constants))

        self.logger.setLevel(log_level)
        self.logger.addHandler(logging.StreamHandler(sys.stdout))

    def newHandler(self, filename, log_level, log_format, logging_constants):
        handler = RotatingFileHandler(
            filename,
            maxBytes=logging_constants.MAXFILESIZE,
            backupCount=logging_constants.BACKUPCOUNT
        )
        handler.setFormatter(log_format)
        handler.setLevel(log_level)
        return handler

    def getLogger(self):
        return self.logger
