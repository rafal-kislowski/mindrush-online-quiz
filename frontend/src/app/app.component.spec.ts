import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render the brand', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand')?.textContent).toContain('MindRush');
  });

  it('should include Library in menu items', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const library = app.menuItems.find((item) => item.route === '/library');
    expect(library?.label).toBe('Library');
    expect(library?.icon).toBe('library');
  });

  it('should compute notification initials from title', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.notificationInitials('Quiz Verification')).toBe('QV');
    expect(app.notificationInitials('A')).toBe('A');
    expect(app.notificationInitials('')).toBe('NT');
  });

  it('should build avatar style with image when image is provided', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const style = app.notificationQuizAvatarStyle({
      avatarImageUrl: 'https://cdn.example/avatar.png',
      avatarBgStart: '#112233',
      avatarBgEnd: '#445566',
      avatarTextColor: '#ffffff',
    });
    expect(style['background-image']).toContain('url(');
    expect(style['background-size']).toBe('cover');
    expect(style['color']).toBe('#ffffff');
  });

  it('should build avatar style with gradient when only colors are provided', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const style = app.notificationQuizAvatarStyle({
      avatarImageUrl: null,
      avatarBgStart: '#112233',
      avatarBgEnd: '#445566',
      avatarTextColor: '#eeeeee',
    });
    expect(style['background-image']).toContain('linear-gradient');
    expect(style['color']).toBe('#eeeeee');
  });

  it('should open current lobby route when lobby code exists', async () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    app.currentLobby = { code: 'ab12cd', maxPlayers: 4, players: [] } as any;
    app.openCurrentLobby();

    expect(navigateSpy).toHaveBeenCalledWith(['/lobby', 'AB12CD'], jasmine.any(Object));
  });

  it('should open current lobby game when active game type is LOBBY', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    app.currentGame = { type: 'LOBBY', lobbyCode: 'xy99zz', gameSessionId: 'ignored' } as any;
    app.openCurrentGame();

    expect(navigateSpy).toHaveBeenCalledWith(['/lobby', 'XY99ZZ', 'game']);
  });

  it('should open current solo game when active game type is SOLO', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    app.currentGame = { type: 'SOLO', gameSessionId: 'solo-123', lobbyCode: null } as any;
    app.openCurrentGame();

    expect(navigateSpy).toHaveBeenCalledWith(['/solo-game', 'solo-123']);
  });

  it('should format notification relative time', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    const now = new Date('2026-03-07T20:00:00.000Z').valueOf();
    spyOn(Date, 'now').and.returnValue(now);

    expect(app.notificationTimeLabel('2026-03-07T19:58:00.000Z')).toBe('2m ago');
    expect(app.notificationTimeLabel('2026-03-07T18:00:00.000Z')).toBe('2h ago');
    expect(app.notificationTimeLabel('2026-03-05T20:00:00.000Z')).toBe('2d ago');
  });
});
