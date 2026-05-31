import { HttpInterceptorFn } from '@angular/common/http';
import { v4 as uuidv4 } from 'uuid';

/** Mints or propagates X-Correlation-Id on every outbound HTTP request. */
export const correlationIdInterceptor: HttpInterceptorFn = (req, next) => {
  const existing = req.headers.get('X-Correlation-Id');
  const correlationId = existing ?? uuidv4();

  const reqWithId = req.clone({
    setHeaders: { 'X-Correlation-Id': correlationId },
  });

  return next(reqWithId);
};
