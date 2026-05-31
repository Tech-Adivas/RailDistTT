import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

// Minimal JWT with payload { "sub": "test-operator" }
// Header: {"alg":"none","typ":"JWT"}
// Payload: {"sub":"test-operator"}
// Signature: empty
const TEST_TOKEN =
  'eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.' +
  btoa(JSON.stringify({ sub: 'test-operator' })).replace(/=/g, '') +
  '.';

describe('AuthService', () => {
  let service: AuthService;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(() => {
    routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    localStorage.clear();

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        { provide: Router, useValue: routerSpy },
      ],
    });

    service = TestBed.inject(AuthService);
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('setToken should update token signal and parse username from JWT sub', () => {
    it('stores the token and extracts the sub claim as currentUser', () => {
      expect(service.token()).toBeNull();
      expect(service.currentUser()).toBe('');

      service.setToken(TEST_TOKEN);

      expect(service.token()).toBe(TEST_TOKEN);
      expect(service.currentUser()).toBe('test-operator');
      expect(service.isAuthenticated()).toBe(true);
      expect(localStorage.getItem('auth_token')).toBe(TEST_TOKEN);
    });
  });

  describe('logout should clear token and user signals', () => {
    it('resets token, currentUser, and localStorage after logout', () => {
      // Set up an authenticated state first
      service.setToken(TEST_TOKEN);
      expect(service.isAuthenticated()).toBe(true);

      service.logout();

      expect(service.token()).toBeNull();
      expect(service.currentUser()).toBe('');
      expect(service.isAuthenticated()).toBe(false);
      expect(localStorage.getItem('auth_token')).toBeNull();
      expect(routerSpy.navigate).toHaveBeenCalledWith(['/']);
    });
  });
});
