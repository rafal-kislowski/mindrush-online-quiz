import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-premium-badge',
  standalone: true,
  templateUrl: './premium-badge.component.html',
  styleUrl: './premium-badge.component.scss',
})
export class PremiumBadgeComponent {
  private static nextGradientId = 0;

  @Input() size = 14;
  @Input() tooltip = 'Premium';

  readonly gradientId = `mrPremiumDiamondGrad${PremiumBadgeComponent.nextGradientId++}`;
}

